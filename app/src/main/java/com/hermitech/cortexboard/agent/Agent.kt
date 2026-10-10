package com.hermitech.cortexboard.agent

import com.hermitech.cortexboard.data.Prefs
import com.hermitech.cortexboard.llm.ChatML
import com.hermitech.cortexboard.llm.GenParams
import com.hermitech.cortexboard.llm.LlamaEngine
import com.hermitech.cortexboard.mcp.McpHub
import com.hermitech.cortexboard.mcp.McpTool
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

sealed interface AgentEvent {
    data class Partial(val text: String) : AgentEvent
    data class ToolCall(val name: String, val args: String) : AgentEvent
    data class ToolResult(val name: String, val result: String, val isError: Boolean) : AgentEvent
    data class Final(val text: String) : AgentEvent
}

data class ChatTurn(val role: String, val content: String)

/**
 * ReAct-style loop over the on-device Qwen model using its native Hermes tool-call format.
 * Tools come from [McpHub]: the local vault server plus any remote MCP servers.
 */
class Agent(private val engine: LlamaEngine, private val hub: McpHub, private val prefs: Prefs) {

    suspend fun run(history: List<ChatTurn>, onEvent: (AgentEvent) -> Unit): String {
        val s = prefs.value
        val tools = hub.tools()
        val system = systemPrompt(tools)
        val working = history.map { ChatML.Msg(it.role, it.content) }.toMutableList()
        val params = GenParams(maxTokens = s.maxTokens, temperature = s.temperature)
        var lastVisible = ""

        repeat(MAX_STEPS) {
            val prompt = fitPrompt(system, working, s.nCtx - s.maxTokens - 32, s.thinking)
            val raw = StringBuilder()
            engine.complete(prompt, params) { chunk ->
                raw.append(chunk)
                val vis = visible(raw.toString())
                if (vis != lastVisible) { lastVisible = vis; onEvent(AgentEvent.Partial(vis)) }
                // small models sometimes hallucinate the tool response — cut them off
                !raw.contains("<tool_response>") && !raw.contains("<|im_start|>")
            }
            val out = raw.toString().substringBefore("<tool_response>").substringBefore("<|im_start|>")
            val calls = parseToolCalls(out)
            if (calls.isEmpty()) {
                val final = visible(out).ifBlank { "(no response)" }
                onEvent(AgentEvent.Final(final))
                return final
            }
            working += ChatML.Msg("assistant", ChatML.stripThinking(out))
            for ((name, args) in calls) {
                onEvent(AgentEvent.ToolCall(name, args.toString()))
                val res = hub.call(name, args)
                val text = res.text.take(MAX_TOOL_CHARS)
                onEvent(AgentEvent.ToolResult(name, text, res.isError))
                working += ChatML.toolResponse(name, text)
            }
            lastVisible = ""
        }
        val msg = "Stopped after $MAX_STEPS tool steps."
        onEvent(AgentEvent.Final(msg))
        return msg
    }

    /** Drop the oldest turns (keeping the latest user turn) until the prompt fits the context budget. */
    private suspend fun fitPrompt(system: String, msgs: MutableList<ChatML.Msg>, budget: Int, thinking: Boolean): String {
        while (true) {
            val prompt = ChatML.build(listOf(ChatML.Msg("system", system)) + msgs, thinking)
            if (msgs.size <= 1 || engine.countTokens(prompt) <= budget) return prompt
            msgs.removeAt(0)
            while (msgs.size > 1 && msgs.first().role != "user") msgs.removeAt(0)
        }
    }

    private fun systemPrompt(tools: List<McpTool>): String {
        val today = LocalDate.now()
        val fns = tools.joinToString("\n") { it.toQwenFunction().toString() }
        return """
You are CORTEX, the on-device brain of CortexBoard: a local-first vault of markdown notes ([[wikilinks]], #tags, folders), task lists and kanban boards.
Today is $today (${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}).
Rules: use tools to read or change the vault; never invent note contents — search or read first. Prefer few, precise tool calls. When done, reply briefly in markdown and say what you changed.

# Tools

You may call one or more functions to assist with the user query.

You are provided with function signatures within <tools></tools> XML tags:
<tools>
$fns
</tools>

For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:
<tool_call>
{"name": <function-name>, "arguments": <args-json-object>}
</tool_call>
        """.trim()
    }

    companion object {
        const val MAX_STEPS = 6
        const val MAX_TOOL_CHARS = 2400
        private val CALL = Regex("<tool_call>\\s*(\\{.*?\\})\\s*(</tool_call>|$)", RegexOption.DOT_MATCHES_ALL)
        private val CALL_BLOCK = Regex("<tool_call>.*?(</tool_call>|$)", RegexOption.DOT_MATCHES_ALL)

        fun visible(raw: String): String = ChatML.stripThinking(raw).replace(CALL_BLOCK, "").trim()

        fun parseToolCalls(text: String): List<Pair<String, JSONObject>> =
            CALL.findAll(ChatML.stripThinking(text)).mapNotNull { m ->
                val o = runCatching { JSONObject(m.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
                val name = o.optString("name").ifBlank { return@mapNotNull null }
                val args = when (val a = o.opt("arguments")) {
                    is JSONObject -> a
                    is String -> runCatching { JSONObject(a) }.getOrDefault(JSONObject())
                    else -> JSONObject()
                }
                name to args
            }.toList()
    }
}
