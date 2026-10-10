package com.hermitech.cortexboard.agent

import com.hermitech.cortexboard.data.Prefs
import com.hermitech.cortexboard.data.Store
import com.hermitech.cortexboard.llm.ChatML
import com.hermitech.cortexboard.llm.GenParams
import com.hermitech.cortexboard.llm.LlamaEngine
import com.hermitech.cortexboard.mcp.McpHub
import com.hermitech.cortexboard.mcp.McpTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

sealed interface AgentEvent {
    data class Partial(val text: String) : AgentEvent
    data class ToolCall(val name: String, val args: String) : AgentEvent
    data class ToolResult(val name: String, val result: String, val isError: Boolean) : AgentEvent
    data class Context(val notes: List<String>) : AgentEvent
    data class Final(val text: String) : AgentEvent
}

data class ChatTurn(val role: String, val content: String)

/**
 * ReAct-style loop over the on-device Qwen model using its native Hermes tool-call format.
 *
 * Prompt layout is ordered for KV-cache reuse: the large, stable system prompt (identity + tool
 * schemas) comes first and never changes between turns, so only new tokens are decoded. Per-turn
 * retrieval context is attached to the latest user message instead of the system prompt.
 */
class Agent(
    private val engine: LlamaEngine,
    private val hub: McpHub,
    private val prefs: Prefs,
    private val store: Store,
) {

    suspend fun run(history: List<ChatTurn>, onEvent: (AgentEvent) -> Unit): String {
        val s = prefs.value
        val tools = hub.tools()
        val system = systemPrompt(tools)
        val working = history.map { ChatML.Msg(it.role, it.content) }.toMutableList()

        // ---- retrieval: ground the small model in the user's own vault
        val lastUser = working.indexOfLast { it.role == "user" }
        if (s.retrieval && lastUser >= 0) {
            val ctx = withContext(Dispatchers.IO) { buildContext(working[lastUser].content) }
            if (ctx != null) {
                onEvent(AgentEvent.Context(ctx.second))
                working[lastUser] = working[lastUser].copy(content = ctx.first + "\n\n" + working[lastUser].content)
            }
        }

        val params = GenParams(maxTokens = s.maxTokens, temperature = s.temperature)
        var lastVisible = ""
        var usedTools = false
        val seenCalls = mutableSetOf<String>()

        repeat(MAX_STEPS) { step ->
            val prompt = fitPrompt(system, working, s.nCtx - s.maxTokens - 32, s.thinking)
            val raw = StringBuilder()
            engine.complete(prompt, params) { chunk ->
                raw.append(chunk)
                val vis = visible(raw.toString())
                if (vis != lastVisible) { lastVisible = vis; onEvent(AgentEvent.Partial(vis)) }
                // small models sometimes hallucinate the tool response or a new turn — cut them off
                !raw.contains("<tool_response>") && !raw.contains("<|im_start|>") && !raw.contains("<|im_end|>")
            }
            val out = raw.toString().substringBefore("<tool_response>").substringBefore("<|im_start|>").substringBefore("<|im_end|>")
            val calls = parseToolCalls(out).filter { (n, a) -> seenCalls.add("$n:$a") } // drop exact repeats (loops)
            if (calls.isEmpty()) {
                val final = visible(out).ifBlank {
                    if (usedTools) "Done." else "(no response — try rephrasing, or switch to a larger model in SYS)"
                }
                onEvent(AgentEvent.Final(final))
                return final
            }
            usedTools = true
            working += ChatML.Msg("assistant", ChatML.stripThinking(out).trim())
            for ((name, args) in calls.take(4)) {
                onEvent(AgentEvent.ToolCall(name, args.toString()))
                val res = hub.call(name, args)
                val text = res.text.take(MAX_TOOL_CHARS)
                onEvent(AgentEvent.ToolResult(name, text, res.isError))
                working += ChatML.toolResponse(name, text)
            }
            // on the last allowed step, nudge for a plain-language wrap-up instead of more calls
            if (step == MAX_STEPS - 2) working += ChatML.Msg("user", "Now answer me directly using the results above. No more tool calls.")
            lastVisible = ""
        }
        val msg = "Stopped after $MAX_STEPS tool steps."
        onEvent(AgentEvent.Final(msg))
        return msg
    }

    /** Top matching notes + open tasks, formatted as compact grounding context. */
    private fun buildContext(query: String): Pair<String, List<String>>? {
        val notes = store.searchAnySync(query, limit = 4)
        val tasks = store.tasksSync(includeDone = false).take(8)
        if (notes.isEmpty() && tasks.isEmpty()) return null
        val sb = StringBuilder("<vault_context>\n")
        notes.forEach { n ->
            val excerpt = n.summary.ifBlank { n.body }.replace(Regex("\\s+"), " ").take(420)
            sb.append("[note: ").append(n.title).append(" | ").append(n.folder).append("] ").append(excerpt).append('\n')
        }
        if (tasks.isNotEmpty()) {
            sb.append("[open tasks] ").append(tasks.joinToString("; ") { t ->
                "#${t.id} ${t.title}" + (if (t.priority > 0) " p${t.priority}" else "") +
                    (t.due?.let { " due ${LocalDate.ofEpochDay(it)}" } ?: "")
            }).append('\n')
        }
        sb.append("</vault_context>\nUse the context above if relevant; call tools to read full notes or make changes.")
        return sb.toString() to notes.map { it.title }
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

    /** Deliberately date-only (no time) so the cached prefix stays valid all day. */
    private fun systemPrompt(tools: List<McpTool>): String {
        val today = LocalDate.now()
        val fns = tools.joinToString("\n") { it.toQwenFunction().toString() }
        return """
You are CORTEX, the on-device brain of CortexBoard: a local-first vault of markdown notes ([[wikilinks]], #tags, folders), task lists and kanban boards.
Today is $today (${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}).
Rules:
- To read or change the vault, call a tool. Never claim you changed something without a tool call.
- Never invent note contents: use the vault context or search/read first.
- Use exact titles and ids from context or tool results.
- One or two precise tool calls per step. When finished, reply briefly in markdown and say what you changed.

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
        // NB: Android's ICU regex engine requires literal braces/brackets to be escaped.
        private val CALL = Regex("<tool_call>\\s*(.*?)\\s*(</tool_call>|$)", RegexOption.DOT_MATCHES_ALL)
        private val CALL_BLOCK = Regex("<tool_call>.*?(</tool_call>|$)", RegexOption.DOT_MATCHES_ALL)
        private val FENCE = Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
        private val TRAILING_COMMA = Regex(",\\s*([\\}\\]])")

        fun visible(raw: String): String = ChatML.stripThinking(raw).replace(CALL_BLOCK, "")
            .replace(FENCE) { m -> if (m.groupValues[1].contains("\"name\"")) "" else m.value }.trim()

        /** Parses a JSON object, tolerating trailing commas, single quotes and surrounding junk. */
        fun lenientJson(text: String): JSONObject? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            var body = text.substring(start, end + 1)
            runCatching { return JSONObject(body) }
            body = body.replace(TRAILING_COMMA, "$1")
            runCatching { return JSONObject(body) }
            if (!body.contains('"')) runCatching { return JSONObject(body.replace('\'', '"')) }
            return null
        }

        fun parseToolCalls(text: String): List<Pair<String, JSONObject>> {
            val clean = ChatML.stripThinking(text)
            val blocks = CALL.findAll(clean).map { it.groupValues[1] }.toMutableList()
            // some small-model outputs drop the tags and emit a fenced json call instead
            if (blocks.isEmpty()) FENCE.findAll(clean).forEach { blocks += it.groupValues[1] }
            return blocks.mapNotNull { b ->
                val o = lenientJson(b) ?: return@mapNotNull null
                val name = o.optString("name").ifBlank { o.optJSONObject("function")?.optString("name").orEmpty() }
                if (name.isBlank()) return@mapNotNull null
                val rawArgs = o.opt("arguments") ?: o.opt("parameters") ?: o.optJSONObject("function")?.opt("arguments")
                val args = when (rawArgs) {
                    is JSONObject -> rawArgs
                    is String -> lenientJson(rawArgs) ?: JSONObject()
                    else -> JSONObject()
                }
                name to args
            }
        }
    }
}
