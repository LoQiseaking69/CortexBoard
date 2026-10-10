package com.hermitech.cortexboard.mcp

import com.hermitech.cortexboard.data.Store
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class ServerStatus(val name: String, val toolCount: Int, val error: String? = null)

/**
 * Aggregates the in-process vault server with any remote MCP servers the user registers.
 * Remote tools are namespaced "<server>__<tool>" so the model can't collide them with vault tools.
 */
class McpHub(private val store: Store, val vault: VaultMcpServer) {
    private var remotes: Map<String, RemoteMcpClient> = emptyMap()
    private val _status = MutableStateFlow<List<ServerStatus>>(emptyList())
    val status: StateFlow<List<ServerStatus>> = _status
    private var tools: List<McpTool> = emptyList()
    private var routes: Map<String, Pair<McpToolProvider, String>> = emptyMap()

    suspend fun refresh(): List<McpTool> = coroutineScope {
        val configs = store.mcpServersSync().filter { it.enabled }
        remotes = configs.associate { cfg ->
            val slug = slug(cfg.name)
            slug to (remotes[slug]?.takeIf { it.config == cfg } ?: RemoteMcpClient(cfg))
        }
        val local = vault.listTools()
        val newRoutes = local.associate { it.name to (vault as McpToolProvider to it.name) }.toMutableMap()
        val statuses = mutableListOf(ServerStatus("vault (local)", local.size))
        val remoteTools = remotes.map { (slug, client) ->
            async {
                val r = withTimeoutOrNull(20_000) { runCatching { client.listTools() } }
                Triple(slug, client, r)
            }
        }.awaitAll().flatMap { (slug, client, r) ->
            val list = r?.getOrNull()
            if (list == null) {
                statuses += ServerStatus(client.name, 0, r?.exceptionOrNull()?.message ?: "timeout")
                emptyList()
            } else {
                statuses += ServerStatus(client.name, list.size)
                list.map { t ->
                    val nsName = "${slug}__${t.name}"
                    newRoutes[nsName] = client to t.name
                    t.copy(name = nsName)
                }
            }
        }
        routes = newRoutes
        tools = local + remoteTools
        _status.value = statuses
        tools
    }

    suspend fun tools(): List<McpTool> = if (tools.isEmpty()) refresh() else tools

    suspend fun call(name: String, args: JSONObject): McpResult {
        if (routes.isEmpty()) refresh()
        val (provider, real) = routes[name] ?: return McpResult("unknown tool '$name'. Available: ${routes.keys.joinToString()}", true)
        return provider.callTool(real, args)
    }

    companion object {
        fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "server" }
    }
}
