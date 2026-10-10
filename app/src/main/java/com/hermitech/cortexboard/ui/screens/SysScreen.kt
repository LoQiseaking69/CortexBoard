package com.hermitech.cortexboard.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hermitech.cortexboard.BuildConfig
import com.hermitech.cortexboard.Cortex
import com.hermitech.cortexboard.agent.Organizer
import com.hermitech.cortexboard.data.McpServerConfig
import com.hermitech.cortexboard.data.Prefs
import com.hermitech.cortexboard.llm.EngineState
import com.hermitech.cortexboard.ui.Glyph
import com.hermitech.cortexboard.ui.KeyValue
import com.hermitech.cortexboard.ui.NeonButton
import com.hermitech.cortexboard.ui.NeonChip
import com.hermitech.cortexboard.ui.NeonPanel
import com.hermitech.cortexboard.ui.SectionHeader
import com.hermitech.cortexboard.ui.TermField
import com.hermitech.cortexboard.ui.theme.Neon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Composable
fun SysScreen(c: Cortex) {
    val ctx = LocalContext.current
    val s by c.prefs.state.collectAsState()
    val engine by c.engine.state.collectAsState()
    val servers by remember { c.store.mcpServers() }.collectAsState(initial = emptyList())
    val status by c.hub.status.collectAsState()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf<String?>(null) }
    val bundled = remember { c.engine.hasBundledModel() }

    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = "copying…"
        c.scope.launch {
            val result = runCatching { copyModel(ctx, uri) }
            withContext(Dispatchers.Main) {
                result.onSuccess { f ->
                    c.prefs.update { it.copy(modelPathOverride = f.absolutePath) }
                    importing = null
                    scope.launch { c.engine.unload(); c.engine.ensureLoaded() }
                }.onFailure { importing = "failed: ${it.message}" }
            }
        }
    }
    val exportVault = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        c.scope.launch {
            val files = c.store.exportMarkdown()
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    ZipOutputStream(os).use { zip ->
                        files.forEach { (path, content) ->
                            zip.putNextEntry(ZipEntry("CortexVault/$path")); zip.write(content.toByteArray()); zip.closeEntry()
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) { Toast.makeText(ctx, "Exported ${files.size} notes", Toast.LENGTH_SHORT).show() }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) {
        // ------------------------------------------------------------ brain
        SectionHeader("NEURAL CORE", Neon.Magenta)
        NeonPanel(Modifier.fillMaxWidth(), accent = Neon.Magenta) {
            val (label, color) = when (val e = engine) {
                EngineState.Idle -> "idle (loads on first use)" to Neon.Dim
                is EngineState.Extracting -> "unpacking weights ${(e.progress * 100).toInt()}%" to Neon.Amber
                is EngineState.Downloading -> "downloading ${(e.progress * 100).toInt()}% · ${e.mb} MB" to Neon.Amber
                EngineState.Loading -> "loading into RAM…" to Neon.Amber
                is EngineState.Ready -> "online · ${e.modelName} · ctx ${e.nCtx}" to Neon.Lime
                is EngineState.Busy -> "generating · ${e.modelName}" to Neon.Magenta
                is EngineState.Error -> "fault: ${e.message}" to Neon.Red
            }
            KeyValue("status", label, color)
            KeyValue("bundled", if (bundled) BuildConfig.MODEL_NAME else "none", Neon.Text)
            KeyValue("override", s.modelPathOverride.ifBlank { "—" }.substringAfterLast('/'))
            KeyValue("runtime", "llama.cpp ${BuildConfig.LLAMA_TAG} · cpu")
            importing?.let { KeyValue("import", it, Neon.Amber) }
            Row(Modifier.padding(top = 8.dp)) {
                NeonButton("BOOT", Neon.Lime) { scope.launch { c.engine.ensureLoaded() } }
                Spacer(Modifier.width(6.dp))
                NeonButton("UNLOAD", Neon.Dim) { scope.launch { c.engine.unload() } }
                Spacer(Modifier.width(6.dp))
                NeonButton("IMPORT GGUF", Neon.Cyan) { importModel.launch(arrayOf("*/*")) }
            }
            if (!bundled) {
                val downloading = engine is EngineState.Downloading
                NeonButton(if (downloading) "DOWNLOADING…" else "DOWNLOAD MODEL (~640 MB)", Neon.Lime, enabled = !downloading,
                    modifier = Modifier.padding(top = 6.dp)) {
                    c.scope.launch { if (c.engine.downloadModel()) c.engine.ensureLoaded() }
                }
                Text("source: ${BuildConfig.MODEL_URL.substringAfter("https://").substringBefore("/resolve")} · Wi-Fi recommended",
                    color = Neon.Faint, style = MaterialTheme.typography.labelSmall)
            }
            if (s.modelPathOverride.isNotBlank()) NeonChip("USE BUNDLED MODEL", accent = Neon.Amber) {
                c.prefs.update { it.copy(modelPathOverride = "") }; scope.launch { c.engine.unload() }
            }
        }

        SectionHeader("INFERENCE", Neon.Cyan, Modifier.padding(top = 10.dp))
        NeonPanel(Modifier.fillMaxWidth()) {
            Text("context window", color = Neon.Dim, style = MaterialTheme.typography.labelSmall)
            LazyRow { listOf(2048, 4096, 8192, 16384).forEach { n -> item { NeonChip("$n", s.nCtx == n) { c.prefs.update { it.copy(nCtx = n) } } } } }
            Text("threads · ${s.threads}", color = Neon.Dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            Slider(s.threads.toFloat(), { v -> c.prefs.update { it.copy(threads = v.toInt()) } }, valueRange = 1f..8f, steps = 6, colors = sliderColors())
            Text("temperature · ${"%.2f".format(s.temperature)}", color = Neon.Dim, style = MaterialTheme.typography.labelSmall)
            Slider(s.temperature, { v -> c.prefs.update { it.copy(temperature = (v * 20).toInt() / 20f) } }, valueRange = 0f..1.2f, colors = sliderColors())
            Text("max output tokens", color = Neon.Dim, style = MaterialTheme.typography.labelSmall)
            LazyRow { listOf(256, 512, 768, 1024, 2048).forEach { n -> item { NeonChip("$n", s.maxTokens == n) { c.prefs.update { it.copy(maxTokens = n) } } } } }
            ToggleRow("reasoning mode (qwen3 <think>)", s.thinking) { v -> c.prefs.update { it.copy(thinking = v) } }
            Text("ctx/thread changes apply on next BOOT", color = Neon.Faint, style = MaterialTheme.typography.labelSmall)
        }

        // ------------------------------------------------------------ organizer
        SectionHeader("AUTO-ORGANIZER", Neon.Lime, Modifier.padding(top = 10.dp))
        NeonPanel(Modifier.fillMaxWidth(), accent = Neon.Lime) {
            ToggleRow("file, tag, summarize & extract tasks automatically", s.autoOrganize) { v ->
                c.prefs.update { it.copy(autoOrganize = v) }; Organizer.schedule(ctx, c.prefs)
            }
            ToggleRow("background runs only while charging", s.organizeOnlyCharging) { v ->
                c.prefs.update { it.copy(organizeOnlyCharging = v) }; Organizer.schedule(ctx, c.prefs)
            }
            NeonButton("RUN NOW", Neon.Lime, modifier = Modifier.padding(top = 6.dp)) { Organizer.kick(ctx) }
        }

        // ------------------------------------------------------------ mcp bridge
        SectionHeader("MCP BRIDGE · SERVE VAULT", Neon.Amber, Modifier.padding(top = 10.dp))
        NeonPanel(Modifier.fillMaxWidth(), accent = Neon.Amber) {
            ToggleRow("expose vault as MCP server on 127.0.0.1", s.bridgeEnabled) { v -> c.prefs.update { it.copy(bridgeEnabled = v) } }
            KeyValue("endpoint", "http://127.0.0.1:${s.bridgePort}/mcp", Neon.Amber)
            KeyValue("token", s.bridgeToken.take(10) + "…")
            Row(Modifier.padding(top = 6.dp)) {
                NeonButton("COPY TOKEN", Neon.Amber) { copy(ctx, "mcp token", s.bridgeToken) }
                Spacer(Modifier.width(6.dp))
                NeonButton("ROTATE", Neon.Red) { c.prefs.update { it.copy(bridgeToken = Prefs.randomToken()) } }
            }
            Text("From a PC: adb forward tcp:${s.bridgePort} tcp:${s.bridgePort}, then point any MCP client at the endpoint with header Authorization: Bearer <token>.",
                color = Neon.Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }

        // ------------------------------------------------------------ remote mcp servers
        SectionHeader("MCP UPLINKS · REMOTE TOOLS", Neon.Cyan, Modifier.padding(top = 10.dp), trailing = {
            NeonChip("REFRESH") { scope.launch { runCatching { c.hub.refresh() } } }
        })
        status.forEach { st ->
            KeyValue(st.name, if (st.error != null) "✕ ${st.error}" else "✓ ${st.toolCount} tools",
                if (st.error != null) Neon.Red else Neon.Lime)
        }
        servers.forEach { sv ->
            NeonPanel(Modifier.fillMaxWidth().padding(vertical = 3.dp), accent = if (sv.enabled) Neon.Cyan else Neon.Faint) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sv.name, color = Neon.Text, style = MaterialTheme.typography.titleSmall)
                        Text(sv.url, color = Neon.Dim, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(sv.enabled, { v -> scope.launch { c.store.saveMcpServer(sv.copy(enabled = v)); c.hub.refresh() } }, colors = switchColors())
                    Glyph("✕", Neon.Red) { scope.launch { c.store.deleteMcpServer(sv.id); c.hub.refresh() } }
                }
            }
        }
        AddServer { cfg -> scope.launch { c.store.saveMcpServer(cfg); runCatching { c.hub.refresh() } } }

        // ------------------------------------------------------------ vault
        SectionHeader("VAULT", Neon.Dim, Modifier.padding(top = 10.dp))
        NeonButton("EXPORT MARKDOWN (.zip)", Neon.Cyan) { exportVault.launch("cortex-vault.zip") }
        Text("CortexBoard ${BuildConfig.VERSION_NAME} · HermiTech", color = Neon.Faint, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun AddServer(onAdd: (McpServerConfig) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    NeonPanel(Modifier.fillMaxWidth().padding(top = 4.dp), accent = Neon.Faint) {
        TermField(name, { name = it }, "name", Modifier.fillMaxWidth())
        TermField(url, { url = it }, "https://host/mcp (streamable http)", Modifier.fillMaxWidth().padding(top = 4.dp))
        TermField(token, { token = it }, "bearer token (optional)", Modifier.fillMaxWidth().padding(top = 4.dp))
        NeonButton("+ UPLINK", enabled = name.isNotBlank() && url.startsWith("http"), modifier = Modifier.padding(top = 6.dp)) {
            onAdd(McpServerConfig(name = name, url = url, token = token)); name = ""; url = ""; token = ""
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Neon.Text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = switchColors())
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Neon.Void, checkedTrackColor = Neon.Cyan,
    uncheckedThumbColor = Neon.Dim, uncheckedTrackColor = Neon.Panel, uncheckedBorderColor = Neon.Faint,
)

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Neon.Cyan, activeTrackColor = Neon.Cyan, inactiveTrackColor = Neon.Faint,
    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)

private fun copy(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

private suspend fun copyModel(ctx: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "custom.gguf"
    require(name.endsWith(".gguf", ignoreCase = true)) { "not a .gguf file" }
    val dir = File(ctx.filesDir, "models/custom").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val out = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
    val magic = out.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
    require(String(magic) == "GGUF") { out.delete(); "file is not GGUF" }
    out
}
