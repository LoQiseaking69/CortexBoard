package com.hermitech.cortexboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermitech.cortexboard.Cortex
import com.hermitech.cortexboard.data.Card
import com.hermitech.cortexboard.ui.Glyph
import com.hermitech.cortexboard.ui.NeonButton
import com.hermitech.cortexboard.ui.NeonChip
import com.hermitech.cortexboard.ui.NeonPanel
import com.hermitech.cortexboard.ui.SectionHeader
import com.hermitech.cortexboard.ui.TermField
import com.hermitech.cortexboard.ui.theme.Neon
import kotlinx.coroutines.launch

private fun colAccent(col: String): Color = when (col) { "TODO" -> Neon.Cyan; "DOING" -> Neon.Amber; else -> Neon.Lime }

@Composable
fun BoardsScreen(c: Cortex) {
    val boards by remember { c.store.boards() }.collectAsState(initial = emptyList())
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var newBoard by rememberSaveable { mutableStateOf("") }
    var newCard by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val board = boards.firstOrNull { it.id == selected } ?: boards.firstOrNull()
    val cards by remember(board?.id) { board?.let { c.store.cards(it.id) } ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
        .collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        LazyRow(verticalAlignment = Alignment.CenterVertically) {
            items(boards, key = { it.id }) { b -> NeonChip("▦ ${b.name}", b.id == board?.id, Neon.Amber) { selected = b.id } }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TermField(newBoard, { newBoard = it }, "new board", Modifier.weight(1f), accent = Neon.Amber)
            Spacer(Modifier.width(6.dp))
            NeonButton("+ BOARD", Neon.Amber, enabled = newBoard.isNotBlank()) {
                val name = newBoard; newBoard = ""
                scope.launch { selected = c.store.addBoard(name).id }
            }
        }
        if (board == null) return@Column
        SectionHeader("${board.name} · ${cards.size} CARDS", Neon.Amber, trailing = {
            if (boards.size > 1) NeonChip("DELETE BOARD", accent = Neon.Red) { scope.launch { c.store.deleteBoard(board.id); selected = null } }
        })
        Row(verticalAlignment = Alignment.CenterVertically) {
            TermField(newCard, { newCard = it }, "+ card → TODO", Modifier.weight(1f), accent = Neon.Cyan, onSubmit = {
                if (newCard.isNotBlank()) { val t = newCard; newCard = ""; scope.launch { c.store.addCard(Card(boardId = board.id, title = t)) } }
            })
            Spacer(Modifier.width(6.dp))
            NeonButton("ADD", enabled = newCard.isNotBlank()) {
                val t = newCard; newCard = ""; scope.launch { c.store.addCard(Card(boardId = board.id, title = t)) }
            }
        }
        LazyRow(
            Modifier.fillMaxSize().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(Card.COLUMNS) { col ->
                val inCol = cards.filter { it.column == col }
                NeonPanel(Modifier.width(270.dp).fillMaxHeight(), accent = colAccent(col)) {
                    Text("$col · ${inCol.size}", color = colAccent(col), style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(inCol, key = { it.id }) { card -> CardView(card,
                            onMove = { dir ->
                                val i = Card.COLUMNS.indexOf(card.column) + dir
                                if (i in Card.COLUMNS.indices) scope.launch { c.store.moveCard(card.id, Card.COLUMNS[i]) }
                            },
                            onDelete = { scope.launch { c.store.deleteCard(card.id) } }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardView(card: Card, onMove: (Int) -> Unit, onDelete: () -> Unit) {
    NeonPanel(Modifier.fillMaxWidth(), accent = Neon.Faint) {
        Text(card.title, color = Neon.Text, style = MaterialTheme.typography.bodyMedium)
        if (card.body.isNotBlank()) Text(card.body, color = Neon.Dim, style = MaterialTheme.typography.bodySmall, maxLines = 4)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${card.id}", color = Neon.Faint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            if (card.column != Card.COLUMNS.first()) Glyph("◀", Neon.Cyan) { onMove(-1) }
            if (card.column != Card.COLUMNS.last()) Glyph("▶", Neon.Lime) { onMove(1) }
            Glyph("✕", Neon.Faint, onDelete)
        }
    }
}
