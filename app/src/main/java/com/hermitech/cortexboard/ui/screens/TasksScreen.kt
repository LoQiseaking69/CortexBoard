package com.hermitech.cortexboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.hermitech.cortexboard.Cortex
import com.hermitech.cortexboard.data.TaskItem
import com.hermitech.cortexboard.ui.ChipShape
import com.hermitech.cortexboard.ui.Glyph
import com.hermitech.cortexboard.ui.NeonButton
import com.hermitech.cortexboard.ui.NeonChip
import com.hermitech.cortexboard.ui.SectionHeader
import com.hermitech.cortexboard.ui.TermField
import com.hermitech.cortexboard.ui.theme.Neon
import kotlinx.coroutines.launch
import java.time.LocalDate

private val PRIORITY = listOf("·" to Neon.Faint, "▂" to Neon.Cyan, "▄" to Neon.Amber, "█" to Neon.Red)

@Composable
fun TasksScreen(c: Cortex, onOpenNote: (Long) -> Unit) {
    val tasks by remember { c.store.tasks() }.collectAsState(initial = emptyList())
    var input by rememberSaveable { mutableStateOf("") }
    var list by rememberSaveable { mutableStateOf("Inbox") }
    var priority by rememberSaveable { mutableStateOf(0) }
    var dueOffset by rememberSaveable { mutableStateOf(-1) } // -1 none, else days from today
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val lists = (listOf("Inbox") + tasks.map { it.list }).distinct()
    val visible = tasks.filter { (filter == null || it.list == filter) && (showDone || !it.done) }
    val today = LocalDate.now().toEpochDay()

    fun add() {
        val t = input.trim()
        if (t.isEmpty()) return
        // inline syntax: "!3 ship build @launch" → priority 3, list "launch"
        var p = priority
        var l = list
        val words = t.split(' ').filter { w ->
            when {
                w.length == 2 && w[0] == '!' && w[1] in '0'..'3' -> { p = w[1] - '0'; false }
                w.length > 1 && w[0] == '@' -> { l = w.drop(1); false }
                else -> true
            }
        }
        val due = if (dueOffset >= 0) LocalDate.now().plusDays(dueOffset.toLong()).toEpochDay() else null
        scope.launch { c.store.addTask(TaskItem(title = words.joinToString(" "), list = l, priority = p, due = due)) }
        input = ""
    }

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TermField(input, { input = it }, "+ task   (!0-3 priority, @list)", Modifier.weight(1f), accent = Neon.Lime, onSubmit = ::add)
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.border(1.dp, PRIORITY[priority].second, ChipShape).clickable { priority = (priority + 1) % 4 }
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) { Text("P$priority", color = PRIORITY[priority].second, style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.width(6.dp))
            NeonButton("ADD", Neon.Lime, enabled = input.isNotBlank(), onClick = ::add)
        }
        LazyRow(Modifier.padding(top = 6.dp)) {
            listOf(-1 to "NO DATE", 0 to "TODAY", 1 to "TOMORROW", 7 to "+1 WEEK").forEach { (d, label) ->
                item { NeonChip("⏱ $label", dueOffset == d, Neon.Amber) { dueOffset = d } }
            }
        }
        LazyRow(Modifier.padding(top = 6.dp)) {
            item { NeonChip("ALL", filter == null, Neon.Lime) { filter = null } }
            items(lists) { l -> NeonChip("@$l", filter == l, Neon.Lime) { filter = l; list = l } }
        }
        val open = tasks.count { !it.done }
        SectionHeader("$open OPEN · ${tasks.size - open} DONE", Neon.Lime, trailing = {
            NeonChip(if (showDone) "HIDE DONE" else "SHOW DONE", showDone, Neon.Lime) { showDone = !showDone }
            if (tasks.any { it.done }) NeonChip("PURGE DONE", accent = Neon.Red) { scope.launch { c.store.clearDone() } }
        })
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            visible.groupBy { it.list }.forEach { (name, group) ->
                item(key = "h-$name") {
                    Text("▸ ${name.uppercase()}", color = Neon.Lime.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp))
                }
                items(group, key = { it.id }) { t -> TaskRow(t, today,
                    onToggle = { scope.launch { c.store.setTaskDone(t.id, !t.done) } },
                    onDelete = { scope.launch { c.store.deleteTask(t.id) } },
                    onOpenNote = t.noteId?.let { nid -> { onOpenNote(nid) } }) }
            }
        }
    }
}

@Composable
private fun TaskRow(t: TaskItem, today: Long, onToggle: () -> Unit, onDelete: () -> Unit, onOpenNote: (() -> Unit)?) {
    val (bar, color) = PRIORITY[t.priority.coerceIn(0, 3)]
    Row(
        Modifier.fillMaxWidth().background(Neon.Panel.copy(alpha = 0.9f), ChipShape).border(1.dp, Neon.Faint, ChipShape)
            .clickable(onClick = onToggle).padding(start = 0.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(40.dp).background(color))
        Text(if (t.done) " ▣" else " □", color = if (t.done) Neon.Lime else Neon.Cyan, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 6.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(t.title, color = if (t.done) Neon.Dim else Neon.Text, style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (t.done) TextDecoration.LineThrough else null)
            val due = t.due
            if (due != null) {
                val overdue = !t.done && due < today
                Text(if (due == today) "due today" else "due ${LocalDate.ofEpochDay(due)}",
                    color = if (overdue) Neon.Red else Neon.Amber, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(bar, color = color, modifier = Modifier.size(16.dp))
        if (onOpenNote != null) Glyph("⟶", Neon.Cyan, onOpenNote)
        Glyph("✕", Neon.Faint, onDelete)
    }
}
