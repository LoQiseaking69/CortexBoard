package com.hermitech.cortexboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermitech.cortexboard.Cortex
import com.hermitech.cortexboard.llm.EngineState
import com.hermitech.cortexboard.ui.screens.BoardsScreen
import com.hermitech.cortexboard.ui.screens.BrainScreen
import com.hermitech.cortexboard.ui.screens.BrainState
import com.hermitech.cortexboard.ui.screens.NoteEditor
import com.hermitech.cortexboard.ui.screens.SysScreen
import com.hermitech.cortexboard.ui.screens.TasksScreen
import com.hermitech.cortexboard.ui.screens.VaultScreen
import com.hermitech.cortexboard.ui.theme.Neon

enum class Tab(val label: String, val glyph: String, val accent: Color) {
    VAULT("VAULT", "▤", Neon.Cyan),
    TASKS("TASKS", "☐", Neon.Lime),
    BOARDS("BOARD", "▦", Neon.Amber),
    BRAIN("BRAIN", "◉", Neon.Magenta),
    SYS("SYS", "⚙", Neon.Dim),
}

@Composable
fun CortexRoot(c: Cortex) {
    var tab by rememberSaveable { mutableStateOf(Tab.VAULT) }
    var openNote by rememberSaveable { mutableStateOf<Long?>(null) }
    val brain = remember { BrainState(c) }

    BackHandler(enabled = openNote != null || tab != Tab.VAULT) {
        if (openNote != null) openNote = null else tab = Tab.VAULT
    }

    CyberBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(c, tab)
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                val id = openNote
                if (id != null) {
                    NoteEditor(c, id, onBack = { openNote = null }, onOpen = { openNote = it })
                } else when (tab) {
                    Tab.VAULT -> VaultScreen(c, onOpen = { openNote = it })
                    Tab.TASKS -> TasksScreen(c, onOpenNote = { openNote = it })
                    Tab.BOARDS -> BoardsScreen(c)
                    Tab.BRAIN -> BrainScreen(c, brain, onOpenSys = { tab = Tab.SYS })
                    Tab.SYS -> SysScreen(c)
                }
            }
            BottomBar(tab) { tab = it; openNote = null }
        }
    }
}

@Composable
private fun TopBar(c: Cortex, tab: Tab) {
    val engine by c.engine.state.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("CORTEX", color = Neon.Cyan, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text("//", color = Neon.Magenta, style = MaterialTheme.typography.headlineSmall)
        Text("BOARD", color = Neon.Text, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.weight(1f))
        val (label, color, busy) = when (val e = engine) {
            EngineState.Idle -> Triple("BRAIN IDLE", Neon.Dim, false)
            is EngineState.Extracting -> Triple("UNPACK ${(e.progress * 100).toInt()}%", Neon.Amber, true)
            is EngineState.Downloading -> Triple("FETCH ${(e.progress * 100).toInt()}%", Neon.Amber, true)
            EngineState.Loading -> Triple("BOOTING", Neon.Amber, true)
            is EngineState.Ready -> Triple("ONLINE", Neon.Lime, false)
            is EngineState.Busy -> Triple("THINKING", Neon.Magenta, true)
            is EngineState.Error -> Triple("FAULT", Neon.Red, false)
        }
        PulseDot(color, busy)
        Text(" $label", color = color, style = MaterialTheme.typography.labelSmall)
    }
    // divider with a slow scan pulse travelling across it
    val scan = rememberInfiniteTransition(label = "scan")
    val x by scan.animateFloat(-0.2f, 1.2f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "x")
    Box(
        Modifier.fillMaxWidth().height(2.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, tab.accent.copy(alpha = 0.6f), Color.Transparent)))
            .drawBehind {
                val cx = size.width * x
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f), Color.Transparent),
                        startX = cx - 60f, endX = cx + 60f),
                )
            }
    )
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Neon.Panel.copy(alpha = 0.95f))
            .border(1.dp, Neon.Faint).padding(vertical = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { t ->
            val sel = t == current
            Column(
                Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.glyph, color = if (sel) t.accent else Neon.Dim, style = MaterialTheme.typography.titleMedium)
                Text(t.label, color = if (sel) t.accent else Neon.Dim, style = MaterialTheme.typography.labelSmall)
                Box(Modifier.padding(top = 3.dp).width(if (sel) 22.dp else 0.dp).height(2.dp).background(t.accent))
            }
        }
    }
}
