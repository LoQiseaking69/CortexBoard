package com.hermitech.cortexboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.hermitech.cortexboard.ui.ChipShape
import androidx.compose.ui.unit.dp
import com.hermitech.cortexboard.Cortex
import com.hermitech.cortexboard.agent.Organizer
import com.hermitech.cortexboard.data.Note
import com.hermitech.cortexboard.data.WikiLinks
import com.hermitech.cortexboard.ui.Glyph
import com.hermitech.cortexboard.ui.MarkdownView
import com.hermitech.cortexboard.ui.NeonButton
import com.hermitech.cortexboard.ui.NeonChip
import com.hermitech.cortexboard.ui.NeonPanel
import com.hermitech.cortexboard.ui.SectionHeader
import com.hermitech.cortexboard.ui.TermField
import com.hermitech.cortexboard.ui.theme.Neon
import com.hermitech.cortexboard.ui.toggleCheckbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun NoteEditor(c: Cortex, id: Long, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val note by remember(id) { c.store.noteFlow(id) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var loaded by remember(id) { mutableStateOf(false) }
    var title by remember(id) { mutableStateOf("") }
    var body by remember(id) { mutableStateOf("") }
    // editor value keeps cursor/IME composition; [body] stays the source of truth for saves & AI ops
    var tfv by remember(id) { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(body) { if (tfv.text != body) tfv = TextFieldValue(body, TextRange(body.length)) }

    fun insert(prefix: String, suffix: String = "", linePrefix: Boolean = false) {
        val t = tfv.text
        val s0 = tfv.selection.min.coerceIn(0, t.length); val e0 = tfv.selection.max.coerceIn(0, t.length)
        if (linePrefix) {
            val ls = t.lastIndexOf('\n', (s0 - 1).coerceAtLeast(0)).let { if (s0 == 0 || it < 0) 0 else it + 1 }
            val nt = t.substring(0, ls) + prefix + t.substring(ls)
            tfv = TextFieldValue(nt, TextRange(s0 + prefix.length)); body = nt
        } else {
            val nt = t.substring(0, s0) + prefix + t.substring(s0, e0) + suffix + t.substring(e0)
            tfv = TextFieldValue(nt, TextRange(if (s0 == e0) s0 + prefix.length else e0 + prefix.length + suffix.length)); body = nt
        }
    }
    var folder by remember(id) { mutableStateOf("") }
    var tags by remember(id) { mutableStateOf("") }
    var preview by remember(id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var aiResult by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val n = note
    LaunchedEffect(n?.id) {
        if (n != null && !loaded) {
            title = n.title; body = n.body; folder = n.folder; tags = n.tags.joinToString(", ")
            preview = n.body.isNotBlank()
            loaded = true
        }
    }

    fun edited(base: Note): Note = base.copy(
        title = title, body = body, folder = folder.ifBlank { "Inbox" },
        tags = tags.split(',').map(WikiLinks::normalizeTag).filter { it.isNotBlank() },
    )
    fun dirty(base: Note) = base.title != title || base.body != body || base.folder != folder.ifBlank { "Inbox" } ||
        base.tags.joinToString(", ") != tags.split(',').map(WikiLinks::normalizeTag).filter { it.isNotBlank() }.joinToString(", ")

    // debounced autosave
    LaunchedEffect(title, body, folder, tags, loaded) {
        val base = note ?: return@LaunchedEffect
        if (!loaded || !dirty(base)) return@LaunchedEffect
        delay(700)
        c.store.upsertNote(edited(base))
    }
    // flush on exit
    val latest = rememberUpdatedState(Triple(note, loaded, Unit))
    DisposableEffect(id) {
        onDispose {
            val base = latest.value.first
            if (base != null && latest.value.second && dirty(base)) {
                val e = edited(base)
                c.scope.launch { c.store.upsertNote(e) }
            }
        }
    }

    fun openLink(target: String) {
        scope.launch {
            val existing = withContext(Dispatchers.IO) { c.store.noteByTitleSync(target) }
            onOpen(existing?.id ?: c.store.upsertNote(Note(title = target, body = "# $target\n\nlinked from [[$title]]\n")))
        }
    }

    fun runAi(label: String, kind: Organizer.Transform) {
        busy = label
        scope.launch {
            aiResult = runCatching { c.organizer.transform(kind, "# $title\n\n$body") }
                .getOrElse { "ERROR: ${it.message}" }
            busy = null
        }
    }

    if (n == null) {
        Text("loading node…", color = Neon.Dim, modifier = Modifier.padding(16.dp))
        return
    }
    val backlinks by remember(n.title) { c.store.backlinks(n.title) }.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph("◀", Neon.Cyan, onBack)
            Text("▸${n.folder}", color = Neon.Dim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Glyph(if (n.pinned) "★" else "☆", Neon.Amber) { scope.launch { c.store.togglePin(edited(n)) } }
            Glyph(if (preview) "✎" else "◉", Neon.Magenta) { preview = !preview }
            Glyph("✕", Neon.Red) { confirmDelete = true }
        }
        TermField(title, { title = it }, "title", Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(top = 6.dp))
        if (preview) {
            NeonPanel(Modifier.fillMaxWidth().heightIn(min = 240.dp), onClick = null) {
                MarkdownView(body, onLink = ::openLink, onToggleCheck = { idx -> body = toggleCheckbox(body, idx) })
            }
        } else {
            LazyRow(Modifier.padding(bottom = 6.dp)) {
                listOf(
                    "H" to { insert("## ", linePrefix = true) }, "☐" to { insert("- [ ] ", linePrefix = true) },
                    "•" to { insert("- ", linePrefix = true) }, "B" to { insert("**", "**") }, "`" to { insert("`", "`") },
                    "[[ ]]" to { insert("[[", "]]") }, "#" to { insert("#") }, "❝" to { insert("> ", linePrefix = true) },
                    "—" to { insert("\n---\n") }, "⏱" to { insert(java.time.LocalTime.now().withSecond(0).withNano(0).toString() + " ") },
                ).forEach { (label, action) -> item { NeonChip(label, accent = Neon.Cyan, onClick = action) } }
            }
            OutlinedTextField(
                value = tfv,
                onValueChange = { tfv = it; if (it.text != body) body = it.text },
                modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
                minLines = 12,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { Text("markdown… [[link]] #tag - [ ] task", color = Neon.Dim, style = MaterialTheme.typography.bodyMedium) },
                shape = ChipShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Neon.Cyan, unfocusedBorderColor = Neon.Faint, cursorColor = Neon.Cyan,
                    focusedTextColor = Neon.Text, unfocusedTextColor = Neon.Text,
                    focusedContainerColor = Neon.Panel.copy(alpha = 0.9f), unfocusedContainerColor = Neon.Panel.copy(alpha = 0.7f),
                ),
            )
            val words = remember(body) { body.split(Regex("\\s+")).count { it.isNotBlank() } }
            Text("$words words · ${WikiLinks.links(body).size} links", color = Neon.Faint, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.padding(top = 8.dp)) {
            TermField(folder, { folder = it }, "folder", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TermField(tags, { tags = it }, "tags, comma", Modifier.weight(1.4f), accent = Neon.Magenta)
        }
        if (n.summary.isNotBlank()) Text("Σ ${n.summary}", color = Neon.Lime, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp))

        SectionHeader("NEURAL OPS", Neon.Magenta, Modifier.padding(top = 10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            item { NeonChip(if (busy == "ORG") "…" else "◆ ORGANIZE", accent = Neon.Lime) {
                busy = "ORG"
                scope.launch {
                    runCatching {
                        c.store.upsertNote(edited(n))
                        val fresh = withContext(Dispatchers.IO) { c.store.noteSync(n.id) } ?: return@runCatching
                        val plan = c.organizer.organize(fresh)
                        if (plan != null) {
                            c.organizer.apply(fresh, plan)
                            val updated = withContext(Dispatchers.IO) { c.store.noteSync(n.id) }
                            if (updated != null) { body = updated.body; folder = updated.folder; tags = updated.tags.joinToString(", ") }
                        }
                    }.onFailure { aiResult = "ERROR: ${it.message}" }
                    busy = null
                }
            } }
            item { NeonChip(if (busy == "SUM") "…" else "Σ SUMMARIZE", accent = Neon.Magenta) { runAi("SUM", Organizer.Transform.SUMMARIZE) } }
            item { NeonChip(if (busy == "CLN") "…" else "⌁ CLEAN", accent = Neon.Magenta) { runAi("CLN", Organizer.Transform.CLEAN) } }
            item { NeonChip(if (busy == "EXP") "…" else "⇲ EXPAND", accent = Neon.Magenta) { runAi("EXP", Organizer.Transform.EXPAND) } }
            item { NeonChip(if (busy == "NXT") "…" else "☐ NEXT STEPS", accent = Neon.Magenta) { runAi("NXT", Organizer.Transform.NEXT_STEPS) } }
            item { NeonChip(if (busy == "CNT") "…" else "✦ CONTINUE", accent = Neon.Magenta) { runAi("CNT", Organizer.Transform.CONTINUE) } }
            item { NeonChip(if (busy == "QST") "…" else "? QUESTIONS", accent = Neon.Magenta) { runAi("QST", Organizer.Transform.QUESTIONS) } }
        }

        SectionHeader("BACKLINKS · ${backlinks.size}", Neon.Cyan, Modifier.padding(top = 10.dp))
        if (backlinks.isEmpty()) Text("no inbound links. reference this node with [[${n.title}]]",
            color = Neon.Faint, style = MaterialTheme.typography.bodySmall)
        backlinks.forEach { b ->
            NeonPanel(Modifier.fillMaxWidth().padding(vertical = 3.dp), accent = Neon.Faint, onClick = { onOpen(b.id) }) {
                Text("⟵ ${b.title}", color = Neon.Cyan, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val outbound = WikiLinks.links(body)
        if (outbound.isNotEmpty()) {
            SectionHeader("OUTBOUND · ${outbound.size}", Neon.Amber, Modifier.padding(top = 6.dp))
            LazyRow { outbound.forEach { t -> item { NeonChip("⟶ $t", accent = Neon.Amber) { openLink(t) } } } }
        }
    }

    aiResult?.let { result ->
        AlertDialog(
            onDismissRequest = { aiResult = null },
            containerColor = Neon.Panel,
            title = { Text("CORTEX OUTPUT", color = Neon.Magenta, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    MarkdownView(result, onLink = {})
                }
            },
            confirmButton = {
                Row {
                    NeonButton("APPEND", Neon.Lime) { body = body.trimEnd() + "\n\n" + result; aiResult = null }
                    Spacer(Modifier.width(8.dp))
                    NeonButton("REPLACE", Neon.Amber) { body = result; aiResult = null }
                }
            },
            dismissButton = { NeonButton("DISCARD", Neon.Dim) { aiResult = null } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = Neon.Panel,
        title = { Text("PURGE NODE?", color = Neon.Red, style = MaterialTheme.typography.titleSmall) },
        text = { Text("“${n.title}” will be deleted permanently.", color = Neon.Text) },
        confirmButton = { NeonButton("PURGE", Neon.Red) { confirmDelete = false; loaded = false; scope.launch { c.store.deleteNote(n.id); onBack() } } },
        dismissButton = { NeonButton("CANCEL", Neon.Dim) { confirmDelete = false } },
    )
}
