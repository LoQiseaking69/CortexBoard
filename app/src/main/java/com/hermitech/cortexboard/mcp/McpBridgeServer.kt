package com.hermitech.cortexboard.mcp

import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Exposes the vault as an MCP server (Streamable HTTP, JSON responses) on 127.0.0.1 only.
 * Every request must carry `Authorization: Bearer <token>`. Reach it from a PC with:
 *   adb forward tcp:8765 tcp:8765   →   http://127.0.0.1:8765/mcp
 */
class McpBridgeServer(private val vault: VaultMcpServer) {
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private val session = UUID.randomUUID().toString()
    val running: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start(port: Int, token: String) {
        stop()
        val ss = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
        server = ss
        Thread({
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (_: Exception) { break }
                pool.execute { runCatching { handle(sock, token) }.onFailure { Log.w(TAG, "conn: ${it.message}") } }
            }
        }, "mcp-bridge").apply { isDaemon = true }.start()
        Log.i(TAG, "MCP bridge on 127.0.0.1:$port")
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(sock: Socket, token: String) { sock.use { s ->
        s.soTimeout = 30_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')

        if (path != "/mcp") return respond(out, 404, "text/plain", "not found")
        val auth = headers["authorization"].orEmpty().removePrefix("Bearer ").trim()
        if (!constantTimeEq(auth, token)) return respond(out, 401, "application/json", """{"error":"unauthorized"}""")
        when (method) {
            "DELETE" -> return respond(out, 204, "text/plain", "")
            "POST" -> Unit
            else -> return respond(out, 405, "text/plain", "method not allowed", "Allow: POST, DELETE\r\n")
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0 || len > 4 shl 20) return respond(out, 400, "text/plain", "bad length")
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
        val req = runCatching { JSONObject(String(body, 0, read, Charsets.UTF_8)) }.getOrNull()
            ?: return respond(out, 400, "application/json", JsonRpc.error(null, -32700, "parse error").toString())
        val res = runBlocking { vault.handleRpc(req) }
        val extra = "Mcp-Session-Id: $session\r\n"
        if (res == null) respond(out, 202, "text/plain", "", extra)
        else respond(out, 200, "application/json", res.toString(), extra)
    } }

    private fun respond(out: OutputStream, code: Int, type: String, body: String, extraHeaders: String = "") {
        val bytes = body.toByteArray()
        val reason = when (code) { 200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Method Not Allowed" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n$extraHeaders\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) return null
        }
        return buf.toString("UTF-8")
    }

    private fun constantTimeEq(a: String, b: String): Boolean =
        b.isNotEmpty() && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object { private const val TAG = "McpBridge" }
}
