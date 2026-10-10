package com.hermitech.cortexboard.ui.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermitech.cortexboard.Cortex
import com.hermitech.cortexboard.agent.AgentEvent
import com.hermitech.cortexboard.agent.ChatTurn
import com.hermitech.cortexboard.llm.EngineState
import com.hermitech.cortexboard.ui.ChipShape
import com.hermitech.cortexboard.ui.MarkdownView
import com.hermitech.cortexboard.ui.NeonButton
import com.hermitech.cortexboard.ui.NeonChip
import com.hermitech.cortexboard.ui.PulseDot
import com.hermitech.cortexboard.ui.TermField
import com.hermitech.cortexboard.ui.theme.Neon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BrainItem {
    data class User(val text: String) : BrainItem
    data class Bot(val text: String, val streaming: Boolean, val stats: String = "") : BrainItem
    data class Tool(val name: String, val args: String, val result: String? = null, val isError: Boolean = false) : BrainItem
    data class Context(val notes: List<String>) : BrainItem
}

/**
 * Conversation state lives above the tab so switching screens doesn't kill a running agent.
 * User turns and final answers are persisted, so the thread survives restarts.
 */
class BrainState(private val c: Cortex) {
    val items = mutableStateListOf<BrainItem>()
    var running by mutableStateOf(false)
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    init {
        scope.launch {
            val rows = withContext(Dispatchers.IO) { runCatching { c.store.chatSync() }.getOrDefault(emptyList()) }
            if (items.isEmpty()) rows.forEach { r ->
                items += if (r.role == "user") BrainItem.User(r.content) else BrainItem.Bot(r.content, false, r.meta)
            }
        }
    }

    fun send(text: String) {
        if (running || text.isBlank()) return
        val msg = text.trim()
        items += BrainItem.User(msg)
        scope.launch { c.store.addChat("user", msg) }
        val history = items.mapNotNull {
            when (it) {
                is BrainItem.User -> ChatTurn("user", it.text)
                is BrainItem.Bot -> if (!it.streaming && !it.text.startsWith("⚠")) ChatTurn("assistant", it.text) else null
                else -> null
            }
        }.takeLast(10)
        running = true
        items += BrainItem.Bot("", streaming = true)
        job = scope.launch {
            try {
                c.agent.run(history) { e -> main.post { onEvent(e) } }
            } catch (t: Throwable) {
                main.post { replaceStreaming("⚠ ${t.message ?: t.javaClass.simpleName}") }
            } finally {
                main.post { finishStreaming(); running = false }
            }
        }
    }

    fun stop() { c.engine.stop(); job?.cancel() }

    fun clear() {
        if (running) return
        items.clear()
        scope.launch { c.store.clearChat() }
    }

    private fun onEvent(e: AgentEvent) {
        when (e) {
            is AgentEvent.Context -> {
                val idx = items.indexOfLast { it is BrainItem.Bot && it.streaming }
                if (e.notes.isNotEmpty()) items.add(if (idx >= 0) idx else items.size, BrainItem.Context(e.notes))
            }
            is AgentEvent.Partial -> replaceStreaming(e.text)
            is AgentEvent.ToolCall -> {
                val idx = items.indexOfLast { it is BrainItem.Bot && it.streaming }
                if (idx >= 0) {
                    val b = items[idx] as BrainItem.Bot
                    if (b.text.isBlank()) items.removeAt(idx) else items[idx] = b.copy(streaming = false)
                }
                items += BrainItem.Tool(e.name, e.args)
            }
            is AgentEvent.ToolResult -> {
                val idx = items.indexOfLast { it is BrainItem.Tool && it.name == e.name && it.result == null }
                if (idx >= 0) items[idx] = (items[idx] as BrainItem.Tool).copy(result = e.result, isError = e.isError)
                items += BrainItem.Bot("", streaming = true)
            }
            is AgentEvent.Final -> replaceStreaming(e.text)
        }
    }

    private fun replaceStreaming(text: String) {
        val idx = items.indexOfLast { it is BrainItem.Bot && it.streaming }
        if (idx >= 0) items[idx] = BrainItem.Bot(text, true) else items += BrainItem.Bot(text, true)
    }

    private fun finishStreaming() {
        val idx = items.indexOfLast { it is BrainItem.Bot && it.streaming }
        if (idx < 0) return
        val b = items[idx] as BrainItem.Bot
        if (b.text.isBlank()) { items.removeAt(idx); return }
        val st = c.engine.stats.value
        val meta = st?.let { "%.1f tok/s · %d tok · first %d ms".format(it.tokensPerSec, it.tokens, it.firstTokenMs) }.orEmpty()
        items[idx] = b.copy(streaming = false, stats = meta)
        if (!b.text.startsWith("⚠")) scope.launch { c.store.addChat("assistant", b.text, meta) }
    }
}

private val QUICK = listOf(
    "☀ Brief" to "Give me a short daily brief: overdue and due-today tasks first, then top priorities, then recent notes worth revisiting.",
    "☐ Plan today" to "Plan my day from my open tasks and log a focus list of the top 3 in my daily note.",
    "▦ Board it" to "Turn my open high-priority tasks into cards on the OPS board.",
    "⟲ Link notes" to "Find notes that should be connected and link the most related pair with [[wikilinks]].",
    "Σ Weekly" to "Summarize what I captured this week and what's still unresolved.",
)

@Composable
fun BrainScreen(c: Cortex, state: BrainState, onOpenSys: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val engine by c.engine.state.collectAsState()
    val hasModel = remember(engine) { c.engine.hasLocalModel() }
    val offline = engine is EngineState.Error || (engine is EngineState.Idle && !hasModel)

    LaunchedEffect(state.items.size, (state.items.lastOrNull() as? BrainItem.Bot)?.text?.length) {
        if (state.items.isNotEmpty()) list.scrollToItem(state.items.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        if (offline) {
            Row(
                Modifier.fillMaxWidth().border(1.dp, Neon.Red.copy(alpha = 0.6f), ChipShape)
                    .background(Neon.Red.copy(alpha = 0.08f), ChipShape).clickable(onClick = onOpenSys).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseDot(Neon.Red, true)
                Text(
                    "  BRAIN OFFLINE · " + ((engine as? EngineState.Error)?.message?.take(80) ?: "no model on device") + "  → SYS",
                    color = Neon.Red, style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.padding(top = 6.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp)) {
            if (state.items.isEmpty()) item {
                Column(Modifier.padding(top = 24.dp)) {
                    Text("◉ CORTEX", color = Neon.Magenta, style = MaterialTheme.typography.displaySmall)
                    Text("on-device Qwen · MCP tool bus · grounded in your vault · zero cloud",
                        color = Neon.Dim, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.padding(top = 16.dp))
                    QUICK.forEach { (label, prompt) ->
                        Text("› $label — ${prompt.take(60)}…", color = Neon.Cyan, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable { state.send(prompt) }.padding(vertical = 8.dp))
                    }
                }
            }
            itemsIndexed(state.items) { _, item ->
                when (item) {
                    is BrainItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(item.text, color = Neon.Void, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.widthIn(max = 300.dp).background(Neon.Cyan, ChipShape).padding(10.dp))
                    }
                    is BrainItem.Bot -> Column(
                        Modifier.fillMaxWidth().animateContentSize()
                            .border(1.dp, Neon.Magenta.copy(alpha = if (item.streaming) 0.9f else 0.45f), ChipShape)
                            .background(Neon.Panel.copy(alpha = 0.92f), ChipShape).padding(10.dp)
                    ) {
                        if (item.text.isBlank() && item.streaming) Row(verticalAlignment = Alignment.CenterVertically) {
                            PulseDot(Neon.Magenta, true)
                            Text("  thinking…", color = Neon.Magenta, style = MaterialTheme.typography.bodySmall)
                        } else MarkdownView(item.text + if (item.streaming) " ▍" else "", onLink = {})
                        if (item.stats.isNotBlank()) Text(item.stats, color = Neon.Faint, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                    is BrainItem.Tool -> ToolTrace(item)
                    is BrainItem.Context -> Text("⌬ grounded in: " + item.notes.joinToString(" · "), color = Neon.Lime.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
        if (state.items.isNotEmpty() && !state.running) LazyRow(Modifier.padding(bottom = 6.dp)) {
            items(QUICK) { (label, prompt) -> NeonChip(label, accent = Neon.Magenta) { state.send(prompt) } }
            item { NeonChip("✕ CLEAR", accent = Neon.Dim) { state.clear() } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            TermField(input, { input = it }, "> instruct the brain", Modifier.weight(1f), accent = Neon.Magenta, singleLine = false)
            Spacer(Modifier.width(6.dp))
            if (state.running) NeonButton("■ STOP", Neon.Red) { state.stop() }
            else NeonButton("SEND", Neon.Magenta, enabled = input.isNotBlank()) { state.send(input); input = "" }
        }
    }
}

@Composable
private fun ToolTrace(t: BrainItem.Tool) {
    var open by remember { mutableStateOf(false) }
    val color = when { t.result == null -> Neon.Amber; t.isError -> Neon.Red; else -> Neon.Lime }
    Column(
        Modifier.fillMaxWidth().animateContentSize().border(1.dp, color.copy(alpha = 0.4f), ChipShape)
            .clickable { open = !open }.padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.result == null) PulseDot(color, true)
            Text("${if (t.result == null) " " else if (t.isError) "✕ " else "✓ "}mcp:${t.name}", color = color,
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(if (open) "▴" else "▾", color = Neon.Dim)
        }
        if (open) {
            Text(t.args, color = Neon.Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            t.result?.let { Text(it.take(1200), color = Neon.Text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}
