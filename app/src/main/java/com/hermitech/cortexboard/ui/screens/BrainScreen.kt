package com.hermitech.cortexboard.ui.screens

import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.hermitech.cortexboard.ui.ChipShape
import com.hermitech.cortexboard.ui.MarkdownView
import com.hermitech.cortexboard.ui.NeonButton
import com.hermitech.cortexboard.ui.NeonChip
import com.hermitech.cortexboard.ui.TermField
import com.hermitech.cortexboard.ui.theme.Neon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed interface BrainItem {
    data class User(val text: String) : BrainItem
    data class Bot(val text: String, val streaming: Boolean) : BrainItem
    data class Tool(val name: String, val args: String, val result: String? = null, val isError: Boolean = false) : BrainItem
}

/** Conversation state lives above the tab so switching screens doesn't kill a running agent. */
class BrainState {
    val items = mutableStateListOf<BrainItem>()
    var running by mutableStateOf(false)
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    fun send(c: Cortex, text: String) {
        if (running || text.isBlank()) return
        items += BrainItem.User(text.trim())
        val history = items.mapNotNull {
            when (it) {
                is BrainItem.User -> ChatTurn("user", it.text)
                is BrainItem.Bot -> if (!it.streaming && !it.text.startsWith("⚠")) ChatTurn("assistant", it.text) else null
                else -> null
            }
        }.takeLast(12)
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

    fun stop(c: Cortex) { c.engine.stop(); job?.cancel() }

    fun clear() { if (!running) items.clear() }

    private fun onEvent(e: AgentEvent) {
        when (e) {
            is AgentEvent.Partial -> replaceStreaming(e.text)
            is AgentEvent.ToolCall -> {
                // freeze the reasoning text (if any) and log the call
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
        if (idx >= 0) {
            val b = items[idx] as BrainItem.Bot
            if (b.text.isBlank()) items.removeAt(idx) else items[idx] = b.copy(streaming = false)
        }
    }
}

private val SUGGESTIONS = listOf(
    "What's on my plate? Summarize open tasks by priority.",
    "Plan today: log a focus list in my daily note.",
    "Create a board for my next launch with 5 starter cards.",
    "Find notes about security and link the related ones.",
)

@Composable
fun BrainScreen(c: Cortex, state: BrainState) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(state.items.size, (state.items.lastOrNull() as? BrainItem.Bot)?.text?.length) {
        if (state.items.isNotEmpty()) list.scrollToItem(state.items.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp)) {
            if (state.items.isEmpty()) item {
                Column(Modifier.padding(top = 24.dp)) {
                    Text("◉ CORTEX", color = Neon.Magenta, style = MaterialTheme.typography.displaySmall)
                    Text("on-device Qwen · MCP tool bus · zero cloud", color = Neon.Dim, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.padding(top = 16.dp))
                    SUGGESTIONS.forEach { s ->
                        Text("› $s", color = Neon.Cyan, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable { state.send(c, s) }.padding(vertical = 8.dp))
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
                        Modifier.fillMaxWidth().border(1.dp, Neon.Magenta.copy(alpha = 0.5f), ChipShape)
                            .background(Neon.Panel.copy(alpha = 0.9f), ChipShape).padding(10.dp)
                    ) {
                        if (item.text.isBlank() && item.streaming) Text("▍ processing…", color = Neon.Magenta, style = MaterialTheme.typography.bodySmall)
                        else MarkdownView(item.text + if (item.streaming) " ▍" else "", onLink = {})
                    }
                    is BrainItem.Tool -> ToolTrace(item)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            TermField(input, { input = it }, "> instruct the brain", Modifier.weight(1f), accent = Neon.Magenta, singleLine = false,
                onSubmit = null)
            Spacer(Modifier.width(6.dp))
            if (state.running) NeonButton("■ STOP", Neon.Red) { state.stop(c) }
            else Column {
                NeonButton("SEND", Neon.Magenta, enabled = input.isNotBlank()) { state.send(c, input); input = "" }
                if (state.items.isNotEmpty()) NeonChip("CLEAR", accent = Neon.Dim) { state.clear() }
            }
        }
    }
}

@Composable
private fun ToolTrace(t: BrainItem.Tool) {
    var open by remember { mutableStateOf(false) }
    val color = when { t.result == null -> Neon.Amber; t.isError -> Neon.Red; else -> Neon.Lime }
    Column(
        Modifier.fillMaxWidth().border(1.dp, color.copy(alpha = 0.4f), ChipShape).clickable { open = !open }.padding(8.dp)
    ) {
        Text("${if (t.result == null) "⟳" else if (t.isError) "✕" else "✓"} mcp:${t.name}", color = color, style = MaterialTheme.typography.labelMedium)
        if (open) {
            Text(t.args, color = Neon.Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            t.result?.let { Text(it.take(1200), color = Neon.Text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}
