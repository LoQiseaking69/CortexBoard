package com.hermitech.cortexboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermitech.cortexboard.Cortex
import com.hermitech.cortexboard.data.Note
import com.hermitech.cortexboard.ui.NeonButton
import com.hermitech.cortexboard.ui.NeonChip
import com.hermitech.cortexboard.ui.NeonPanel
import com.hermitech.cortexboard.ui.SectionHeader
import com.hermitech.cortexboard.ui.TermField
import com.hermitech.cortexboard.ui.theme.Neon
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VaultScreen(c: Cortex, onOpen: (Long) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var capture by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val notesFlow = remember(query, folder, tag) { c.store.notes(query, folder, tag) }
    val notes by notesFlow.collectAsState(initial = emptyList())
    val facets by remember { c.store.facets() }.collectAsState(initial = emptyList<String>() to emptyList())
    val pending = notes.count { !it.organized }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TermField(capture, { capture = it }, "⚡ capture a thought → AI files it", Modifier.weight(1f), accent = Neon.Magenta,
                onSubmit = { c.capture(capture); capture = "" })
            Spacer(Modifier.width(8.dp))
            NeonButton("⏎", Neon.Magenta, enabled = capture.isNotBlank()) { c.capture(capture); capture = "" }
        }
        Spacer(Modifier.padding(top = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TermField(query, { query = it }, "> search vault", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            NeonButton("+ NOTE") {
                scope.launch { onOpen(c.store.upsertNote(Note(title = "Untitled", folder = folder ?: "Inbox"))) }
            }
        }
        LazyRow(Modifier.padding(top = 8.dp)) {
            item { NeonChip("ALL", folder == null && tag == null) { folder = null; tag = null } }
            items(facets.first) { f -> NeonChip("▸$f", folder == f) { folder = if (folder == f) null else f } }
        }
        if (facets.second.isNotEmpty()) LazyRow(Modifier.padding(top = 6.dp)) {
            items(facets.second) { t -> NeonChip("#$t", tag == t, Neon.Magenta) { tag = if (tag == t) null else t } }
        }
        SectionHeader("${notes.size} NODES" + if (pending > 0) " · $pending UNSORTED" else "", trailing = {
            if (pending > 0) NeonButton(status ?: "ORGANIZE", Neon.Lime) {
                status = "…"
                scope.launch {
                    val n = runCatching { c.organizer.runPending(12) }.getOrDefault(0)
                    status = if (n > 0) "✓ $n" else "ORGANIZE"
                }
            }
        })
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(notes, key = { it.id }) { n -> NoteRow(n) { onOpen(n.id) } }
        }
    }
}

private val dateFmt = SimpleDateFormat("MMM d · HH:mm", Locale.US)

@Composable
private fun NoteRow(n: Note, onClick: () -> Unit) {
    NeonPanel(Modifier.fillMaxWidth(), accent = if (n.pinned) Neon.Amber else if (n.organized) Neon.Cyan else Neon.Magenta, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (n.pinned) Text("★ ", color = Neon.Amber)
            Text(n.title, style = MaterialTheme.typography.titleMedium, color = Neon.Text, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (n.organized) "◆" else "◇", color = if (n.organized) Neon.Lime else Neon.Magenta)
        }
        val preview = n.summary.ifBlank { n.body.replace(Regex("[#*>`\\[\\]-]"), "").replace(Regex("\\s+"), " ").trim() }
        if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.bodySmall, color = Neon.Dim,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 6.dp)) {
            Text("▸${n.folder}", color = Neon.Cyan.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            Box(Modifier.weight(1f)) {
                Text(n.tags.take(4).joinToString(" ") { "#$it" }, color = Neon.Magenta.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
            Text(dateFmt.format(Date(n.updated)), color = Neon.Faint, style = MaterialTheme.typography.labelSmall)
        }
    }
}
