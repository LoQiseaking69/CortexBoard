package com.hermitech.cortexboard.mcp

import com.hermitech.cortexboard.BuildConfig
import com.hermitech.cortexboard.data.McpServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** MCP client over the Streamable HTTP transport (JSON or SSE responses). */
class RemoteMcpClient(val config: McpServerConfig) : McpToolProvider {
    override val name: String = config.name
    private val ids = AtomicInteger(1)
    private val lock = Mutex()
    private var sessionId: String? = null
    private var initialized = false
    private var cachedTools: List<McpTool>? = null

    private suspend fun ensureInit() = lock.withLock {
        if (initialized) return@withLock
        val res = post(JsonRpc.request(ids.getAndIncrement(), "initialize", JSONObject()
            .put("protocolVersion", MCP_PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "cortexboard").put("version", BuildConfig.VERSION_NAME))))
        res ?: throw IOException("no initialize response")
        res.optJSONObject("error")?.let { throw IOException(it.optString("message")) }
        post(JsonRpc.notification("notifications/initialized"))
        initialized = true
    }

    override suspend fun listTools(): List<McpTool> {
        cachedTools?.let { return it }
        ensureInit()
        val all = mutableListOf<McpTool>()
        var cursor: String? = null
        do {
            val params = JSONObject().also { p -> cursor?.let { p.put("cursor", it) } }
            val res = rpc("tools/list", params)
            val arr = res.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                all += McpTool(
                    t.getString("name"), t.optString("description").take(300),
                    t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"), config.name,
                )
            }
            cursor = res.optString("nextCursor").ifBlank { null }
        } while (cursor != null && all.size < 200)
        cachedTools = all
        return all
    }

    override suspend fun callTool(name: String, args: JSONObject): McpResult = try {
        ensureInit()
        McpResult.fromJson(rpc("tools/call", JSONObject().put("name", name).put("arguments", args)))
    } catch (e: Exception) {
        McpResult("ERROR (${config.name}): ${e.message}", true)
    }

    private suspend fun rpc(method: String, params: JSONObject): JSONObject {
        val res = post(JsonRpc.request(ids.getAndIncrement(), method, params)) ?: throw IOException("empty response")
        res.optJSONObject("error")?.let { throw IOException("${it.optInt("code")}: ${it.optString("message")}") }
        return res.optJSONObject("result") ?: JSONObject()
    }

    private suspend fun post(msg: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val conn = (URL(config.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (initialized) setRequestProperty("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            if (config.token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.token}")
        }
        try {
            conn.outputStream.use { it.write(msg.toString().toByteArray()) }
            val code = conn.responseCode
            conn.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
            if (code == 202 || code == 204) return@withContext null
            if (code == 404 && sessionId != null) { // session expired: re-init on next call
                initialized = false; sessionId = null
                throw IOException("session expired")
            }
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("HTTP $code ${err.take(200)}")
            }
            val type = conn.contentType.orEmpty()
            val wantId = if (msg.has("id")) msg.get("id").toString() else null
            if (type.startsWith("text/event-stream")) {
                conn.inputStream.bufferedReader().use { r ->
                    val data = StringBuilder()
                    while (true) {
                        val line = r.readLine() ?: break
                        when {
                            line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
                            line.isEmpty() && data.isNotEmpty() -> {
                                val obj = runCatching { JSONObject(data.toString()) }.getOrNull()
                                data.setLength(0)
                                if (obj != null && obj.has("id") && obj.get("id").toString() == wantId) return@withContext obj
                            }
                        }
                    }
                    if (data.isNotEmpty()) runCatching { JSONObject(data.toString()) }.getOrNull() else null
                }
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                if (body.isBlank()) null else JSONObject(body)
            }
        } finally {
            conn.disconnect()
        }
    }
}
