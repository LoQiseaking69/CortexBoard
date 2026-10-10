package com.hermitech.cortexboard.llm

import android.content.Context
import com.hermitech.cortexboard.BuildConfig
import com.hermitech.cortexboard.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import kotlin.coroutines.coroutineContext

fun interface TokenCallback {
    /** Called from native code with a complete UTF-8 chunk. Return false to stop. */
    fun onBytes(bytes: ByteArray): Boolean
}

object LlamaNative {
    init { System.loadLibrary("cortex") }
    external fun nativeInit(libDir: String)
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    external fun nativeFree(handle: Long)
    external fun nativeCountTokens(handle: Long, text: ByteArray): Int
    external fun nativeAbort()
    external fun nativeGenerate(
        handle: Long, prompt: ByteArray, maxTokens: Int, temp: Float, topP: Float, topK: Int,
        repeatPenalty: Float, seed: Int, callback: TokenCallback,
    ): Int
}

sealed interface EngineState {
    data object Idle : EngineState
    data class Extracting(val progress: Float) : EngineState
    data class Downloading(val progress: Float, val mb: Long) : EngineState
    data object Loading : EngineState
    data class Ready(val modelName: String, val nCtx: Int) : EngineState
    data class Busy(val modelName: String) : EngineState
    data class Error(val message: String) : EngineState
}

data class GenParams(
    val maxTokens: Int = 768,
    val temperature: Float = 0.6f,
    val topP: Float = 0.95f,
    val topK: Int = 20,
    val repeatPenalty: Float = 1.05f,
)

class LlmException(msg: String) : Exception(msg)

data class GenStats(val promptChars: Int, val tokens: Int, val firstTokenMs: Long, val tokensPerSec: Float)

/**
 * Owns the single llama.cpp session. All native calls are serialized through [lock]
 * on a dedicated single thread, so callers can be any coroutine.
 */
class LlamaEngine(private val context: Context, private val prefs: Prefs) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llmThread = Dispatchers.IO.limitedParallelism(1)
    private val lock = Mutex()
    private var handle = 0L
    private var loadedKey = ""
    private var nativeReady = false

    val isReady: Boolean get() = handle != 0L
    private val _stats = MutableStateFlow<GenStats?>(null)
    /** Throughput of the most recent generation, for the UI. */
    val stats: StateFlow<GenStats?> = _stats

    private fun bundledModelFile(): File = File(context.filesDir, "models/${BuildConfig.MODEL_NAME}.gguf")

    fun hasBundledModel(): Boolean = try {
        context.assets.openFd(ASSET).use { true }
    } catch (_: FileNotFoundException) {
        try { context.assets.open(ASSET).use { true } } catch (_: Exception) { false }
    } catch (_: Exception) { false }

    /** Copies the APK-embedded GGUF to internal storage once (needed for mmap by path). */
    private fun extractIfNeeded(): File {
        val out = bundledModelFile()
        val expected = try { context.assets.openFd(ASSET).use { it.length } } catch (_: Exception) { -1L }
        if (out.exists() && (expected < 0 || out.length() == expected)) return out
        out.parentFile?.mkdirs()
        out.parentFile?.listFiles()?.forEach { if (it.name.endsWith(".gguf")) it.delete() } // drop stale versions
        val tmp = File(out.parentFile, out.name + ".part")
        context.assets.open(ASSET).use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    copied += n
                    if (expected > 0 && copied - lastReport > (8 shl 20)) {
                        lastReport = copied
                        _state.value = EngineState.Extracting(copied.toFloat() / expected)
                    }
                }
            }
        }
        if (!tmp.renameTo(out)) throw LlmException("could not finalize model file")
        return out
    }

    /** Downloadable Qwen3 builds. All are official Qwen GGUF releases on Hugging Face. */
    data class ModelPreset(val id: String, val label: String, val url: String, val sizeMb: Int, val note: String) {
        val fileName: String get() = url.substringAfterLast('/').substringBefore('?')
    }

    private fun downloadDir() = File(context.filesDir, "models/download")
    fun presetFile(p: ModelPreset): File = File(downloadDir(), p.fileName)
    private fun defaultPreset(): ModelPreset =
        PRESETS.firstOrNull { it.url == BuildConfig.MODEL_URL } ?: ModelPreset("default", "Default", BuildConfig.MODEL_URL, 640, "")

    /** A downloaded file is trusted only if its recorded size matches. */
    private fun verified(f: File): Boolean {
        if (!f.exists()) return false
        val expected = File(f.path + ".size").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        return expected != null && expected == f.length()
    }

    fun isDownloaded(p: ModelPreset): Boolean = verified(presetFile(p))
    fun hasDownloadedModel(): Boolean = isDownloaded(defaultPreset())

    /** Path of the model that will be used, for UI. */
    fun activeModelPath(): String = prefs.value.modelPathOverride.ifBlank { if (hasBundledModel()) "bundled" else presetFile(defaultPreset()).path }

    /** True when some model is available without a download (override, bundled, or already fetched). */
    fun hasLocalModel(): Boolean =
        prefs.value.modelPathOverride.let { it.isNotBlank() && File(it).exists() } ||
            hasBundledModel() || downloadDir().listFiles()?.any { it.name.endsWith(".gguf") && verified(it) } == true

    private fun resolveModel(): Pair<File, String> {
        val override = prefs.value.modelPathOverride
        if (override.isNotBlank()) {
            val f = File(override)
            if (f.exists() && (f.parentFile != downloadDir() || verified(f))) return f to f.nameWithoutExtension
        }
        if (hasBundledModel()) return extractIfNeeded() to BuildConfig.MODEL_NAME
        val any = downloadDir().listFiles()?.filter { it.name.endsWith(".gguf") && verified(it) }?.maxByOrNull { it.lastModified() }
        if (any != null) return any to any.nameWithoutExtension
        downloadDir().listFiles()?.filter { it.name.endsWith(".gguf") }?.forEach { it.delete() } // unverified leftovers
        throw LlmException("No model on device. SYS → pick a model → DOWNLOAD, or IMPORT GGUF.")
    }

    private val downloadLock = Mutex()
    val isDownloading: Boolean get() = downloadLock.isLocked
    private val _downloadingId = MutableStateFlow<String?>(null)
    val downloadingId: StateFlow<String?> = _downloadingId

    /** Makes a downloaded preset the active model (reloads on next use). */
    suspend fun activate(p: ModelPreset) {
        prefs.update { it.copy(modelPathOverride = presetFile(p).absolutePath) }
        unload()
    }

    suspend fun deletePreset(p: ModelPreset) {
        val f = presetFile(p)
        if (prefs.value.modelPathOverride == f.absolutePath) prefs.update { it.copy(modelPathOverride = "") }
        unload()
        withContext(Dispatchers.IO) { listOf(f, File(f.path + ".part"), File(f.path + ".size")).forEach { it.delete() } }
    }

    suspend fun deleteDownloadedModel() = deletePreset(defaultPreset())

    /** Downloads (with resume) and activates a preset. Single-flight: returns false if one is running. */
    suspend fun downloadModel(p: ModelPreset = defaultPreset()): Boolean {
        if (!downloadLock.tryLock()) return false
        _downloadingId.value = p.id
        try {
            val ok = downloadLocked(p.url, presetFile(p))
            if (ok) activate(p)
            return ok
        } finally {
            _downloadingId.value = null
            downloadLock.unlock()
        }
    }

    private suspend fun downloadLocked(url: String, out: File): Boolean = withContext(Dispatchers.IO) {
        if (verified(out)) return@withContext true
        if (!(out.parentFile?.let { it.isDirectory || it.mkdirs() } ?: false)) {
            _state.value = EngineState.Error("cannot create ${out.parentFile}")
            return@withContext false
        }
        val part = File(out.parentFile, out.name + ".part")
        _state.value = EngineState.Downloading(0f, part.length() shr 20)
        try {
            var target = url
            var conn: java.net.HttpURLConnection
            var redirects = 0
            while (true) { // follow redirects manually (HF resolve → CDN, cross-host)
                conn = (java.net.URL(target).openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "CortexBoard/${BuildConfig.VERSION_NAME}")
                    if (part.exists() && part.length() > 0) setRequestProperty("Range", "bytes=${part.length()}-")
                }
                val code = conn.responseCode
                if (code in 300..399 && redirects < 8) {
                    target = java.net.URL(java.net.URL(target), conn.getHeaderField("Location")).toString()
                    conn.disconnect(); redirects++; continue
                }
                if (code == 416) { conn.disconnect(); part.delete(); throw LlmException("stale partial file reset — tap DOWNLOAD again") }
                if (code != 200 && code != 206) throw LlmException("download failed: HTTP $code")
                break
            }
            val resumed = conn.responseCode == 206
            if (!resumed) part.delete()
            val already = if (resumed) part.length() else 0L
            val total = already + conn.contentLengthLong.coerceAtLeast(0)
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, resumed).use { output ->
                    val buf = ByteArray(1 shl 20)
                    var done = already
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done - lastReport > (4 shl 20)) {
                            lastReport = done
                            _state.value = EngineState.Downloading(if (total > 0) done.toFloat() / total else 0f, done shr 20)
                        }
                    }
                }
            }
            conn.disconnect()
            if (total > already && part.length() != total) {
                throw LlmException("incomplete download (${part.length() shr 20}/${total shr 20} MB) — tap DOWNLOAD to resume")
            }
            val magic = part.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
            if (String(magic) != "GGUF") { part.delete(); throw LlmException("downloaded file is not GGUF") }
            out.delete()
            if (!part.renameTo(out)) throw LlmException("could not finalize download")
            File(out.path + ".size").writeText(out.length().toString())
            _state.value = EngineState.Idle
            true
        } catch (e: Throwable) {
            if (e is CancellationException) { _state.value = EngineState.Idle; throw e }
            _state.value = EngineState.Error(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    suspend fun ensureLoaded(): Boolean = withContext(llmThread) {
        lock.withLock {
            val s = prefs.value
            try {
                if (!nativeReady) {
                    LlamaNative.nativeInit(context.applicationInfo.nativeLibraryDir)
                    nativeReady = true
                }
                val (file, name) = resolveModel()
                val key = "${file.absolutePath}|${s.nCtx}|${s.threads}"
                if (handle != 0L && key == loadedKey) return@withLock true
                if (handle != 0L) { LlamaNative.nativeFree(handle); handle = 0L }
                _state.value = EngineState.Loading
                val h = LlamaNative.nativeLoad(file.absolutePath, s.nCtx, s.threads)
                if (h == 0L) throw LlmException("llama.cpp failed to load ${file.name}")
                handle = h
                loadedKey = key
                _state.value = EngineState.Ready(name, s.nCtx)
                true
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.value = EngineState.Error(e.message ?: e.javaClass.simpleName)
                false
            }
        }
    }

    suspend fun unload() = withContext(llmThread) {
        lock.withLock {
            if (handle != 0L) LlamaNative.nativeFree(handle)
            handle = 0L; loadedKey = ""
            _state.value = EngineState.Idle
        }
    }

    suspend fun countTokens(text: String): Int = withContext(llmThread) {
        lock.withLock { if (handle == 0L) text.length / 4 else LlamaNative.nativeCountTokens(handle, text.toByteArray()) }
    }

    fun stop() { if (nativeReady) LlamaNative.nativeAbort() }

    /**
     * Raw completion. [onToken] receives decoded text chunks; return false to stop early
     * (used by the agent to cut generation right after a closing </tool_call>).
     */
    suspend fun complete(prompt: String, params: GenParams, onToken: (String) -> Boolean = { true }): String {
        if (!ensureLoaded()) throw LlmException((state.value as? EngineState.Error)?.message ?: "model unavailable")
        val job = coroutineContext
        return withContext(llmThread) {
            lock.withLock {
                val ready = state.value
                val name = (ready as? EngineState.Ready)?.modelName ?: BuildConfig.MODEL_NAME
                _state.value = EngineState.Busy(name)
                val sb = StringBuilder()
                val t0 = System.nanoTime()
                var tFirst = 0L
                try {
                    val rc = LlamaNative.nativeGenerate(
                        handle, prompt.toByteArray(), params.maxTokens, params.temperature, params.topP,
                        params.topK, params.repeatPenalty, (System.nanoTime() and 0x7fffffff).toInt(),
                    ) { bytes ->
                        val chunk = String(bytes, Charsets.UTF_8)
                        if (tFirst == 0L) tFirst = System.nanoTime()
                        sb.append(chunk)
                        job.isActive && onToken(chunk)
                    }
                    when (rc) {
                        -2 -> throw LlmException("prompt exceeds context window (${prefs.value.nCtx} tokens)")
                        -3 -> throw LlmException("decode failed")
                    }
                    if (rc > 0 && tFirst > 0) {
                        val decodeS = (System.nanoTime() - tFirst) / 1e9f
                        _stats.value = GenStats(prompt.length, rc, (tFirst - t0) / 1_000_000, if (decodeS > 0f) rc / decodeS else 0f)
                    }
                    sb.toString()
                } finally {
                    _state.value = EngineState.Ready(name, prefs.value.nCtx)
                }
            }
        }
    }

    companion object {
        const val ASSET = "models/model.gguf"
        private const val HF = "https://huggingface.co/Qwen"
        val PRESETS = listOf(
            ModelPreset("q3-0.6b", "Qwen3 0.6B · Q8", "$HF/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf", 639, "fastest · any phone"),
            ModelPreset("q3-1.7b", "Qwen3 1.7B · Q8", "$HF/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q8_0.gguf", 1830, "smarter tools · 6 GB+ RAM"),
            ModelPreset("q3-4b", "Qwen3 4B · Q4_K_M", "$HF/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf", 2500, "best reasoning · 8 GB+ RAM"),
        )
    }
}
