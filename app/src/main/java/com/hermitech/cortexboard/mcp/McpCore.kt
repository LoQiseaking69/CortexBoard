package com.hermitech.cortexboard.mcp

import org.json.JSONArray
import org.json.JSONObject

const val MCP_PROTOCOL_VERSION = "2025-06-18"

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    /** "local" or the remote server name */
    val source: String = "local",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("description", description).put("inputSchema", inputSchema)

    /** Qwen/Hermes function-calling signature. */
    fun toQwenFunction(): JSONObject = JSONObject().put("type", "function").put(
        "function", JSONObject().put("name", name).put("description", description).put("parameters", inputSchema)
    )
}

data class McpResult(val text: String, val isError: Boolean = false) {
    fun toJson(): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", isError)

    companion object {
        fun fromJson(o: JSONObject): McpResult {
            val content = o.optJSONArray("content") ?: JSONArray()
            val text = buildString {
                for (i in 0 until content.length()) {
                    val c = content.optJSONObject(i) ?: continue
                    when (c.optString("type")) {
                        "text" -> appendLine(c.optString("text"))
                        "resource" -> appendLine(c.optJSONObject("resource")?.optString("text").orEmpty())
                        else -> appendLine("[${c.optString("type")} content]")
                    }
                }
                if (isEmpty() && o.has("structuredContent")) append(o.getJSONObject("structuredContent").toString())
            }.trim()
            return McpResult(text, o.optBoolean("isError", false))
        }
    }
}

interface McpToolProvider {
    val name: String
    suspend fun listTools(): List<McpTool>
    suspend fun callTool(name: String, args: JSONObject): McpResult
}

/** Tiny schema builder to keep tool declarations readable and token-cheap. */
class SchemaBuilder {
    private val props = JSONObject()
    private val required = JSONArray()

    fun str(name: String, desc: String, req: Boolean = false) = prop(name, "string", desc, req)
    fun int(name: String, desc: String, req: Boolean = false) = prop(name, "integer", desc, req)
    fun bool(name: String, desc: String, req: Boolean = false) = prop(name, "boolean", desc, req)
    fun strList(name: String, desc: String, req: Boolean = false) = apply {
        props.put(name, JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", desc))
        if (req) required.put(name)
    }
    fun enumStr(name: String, desc: String, values: List<String>, req: Boolean = false) = apply {
        props.put(name, JSONObject().put("type", "string").put("enum", JSONArray(values)).put("description", desc))
        if (req) required.put(name)
    }

    private fun prop(name: String, type: String, desc: String, req: Boolean) = apply {
        props.put(name, JSONObject().put("type", type).put("description", desc))
        if (req) required.put(name)
    }

    fun build(): JSONObject = JSONObject().put("type", "object").put("properties", props).also {
        if (required.length() > 0) it.put("required", required)
    }
}

fun schema(block: SchemaBuilder.() -> Unit): JSONObject = SchemaBuilder().apply(block).build()

object JsonRpc {
    fun request(id: Any, method: String, params: JSONObject? = null): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).also { if (params != null) it.put("params", params) }

    fun notification(method: String, params: JSONObject? = null): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("method", method).also { if (params != null) it.put("params", params) }

    fun result(id: Any?, result: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)

    fun error(id: Any?, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
}
