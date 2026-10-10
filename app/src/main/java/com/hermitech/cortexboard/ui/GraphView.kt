package com.hermitech.cortexboard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermitech.cortexboard.Cortex
import com.hermitech.cortexboard.data.Store
import com.hermitech.cortexboard.ui.theme.Mono
import com.hermitech.cortexboard.ui.theme.Neon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

private val FOLDER_COLORS = listOf(Neon.Cyan, Neon.Magenta, Neon.Lime, Neon.Amber, Color(0xFF8A7CFF), Color(0xFF4DD0FF))

private class Layout(val pos: Map<Long, Offset>, val degree: Map<Long, Int>)

/** Fruchterman–Reingold with gravity; deterministic per note id so the graph doesn't jump around. */
private fun layout(g: Store.GraphData): Layout {
    val ids = g.nodes.map { it.first }
    val n = ids.size
    if (n == 0) return Layout(emptyMap(), emptyMap())
    val index = ids.withIndex().associate { it.value to it.index }
    val xs = FloatArray(n); val ys = FloatArray(n)
    ids.forEachIndexed { i, id ->
        val r = Random(id)
        xs[i] = (r.nextFloat() - 0.5f) * 600f; ys[i] = (r.nextFloat() - 0.5f) * 600f
    }
    val edges = g.edges.mapNotNull { (a, b) -> val i = index[a]; val j = index[b]; if (i != null && j != null) i to j else null }
    val k = sqrt(640_000f / n) * 0.9f
    var temp = 120f
    val dx = FloatArray(n); val dy = FloatArray(n)
    repeat(220) {
        dx.fill(0f); dy.fill(0f)
        for (i in 0 until n) for (j in i + 1 until n) {
            val ddx = xs[i] - xs[j]; val ddy = ys[i] - ys[j]
            val d2 = max(ddx * ddx + ddy * ddy, 0.01f)
            val f = k * k / d2
            dx[i] += ddx * f; dy[i] += ddy * f; dx[j] -= ddx * f; dy[j] -= ddy * f
        }
        for ((i, j) in edges) {
            val ddx = xs[i] - xs[j]; val ddy = ys[i] - ys[j]
            val d = max(sqrt(ddx * ddx + ddy * ddy), 0.01f)
            val f = d / k
            dx[i] -= ddx * f; dy[i] -= ddy * f; dx[j] += ddx * f; dy[j] += ddy * f
        }
        for (i in 0 until n) {
            dx[i] -= xs[i] * 0.02f; dy[i] -= ys[i] * 0.02f // gravity keeps islands on screen
            val d = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), 0.01f)
            val step = min(d, temp)
            xs[i] += dx[i] / d * step; ys[i] += dy[i] / d * step
        }
        temp *= 0.975f
    }
    val degree = HashMap<Long, Int>()
    g.edges.forEach { (a, b) -> degree[a] = (degree[a] ?: 0) + 1; degree[b] = (degree[b] ?: 0) + 1 }
    return Layout(ids.withIndex().associate { it.value to Offset(xs[it.index], ys[it.index]) }, degree)
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun GraphView(c: Cortex, onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
    val data by remember { c.store.graph() }.collectAsState(initial = Store.GraphData(emptyList(), emptyList(), emptyMap()))
    var lay by remember { mutableStateOf<Layout?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = Mono, fontSize = 10.sp, color = Neon.Text)

    LaunchedEffect(data) { lay = withContext(Dispatchers.Default) { layout(data) } }

    val folders = remember(data) { data.folders.values.distinct().sorted() }
    fun colorOf(id: Long): Color {
        val f = data.folders[id] ?: return Neon.Dim
        return FOLDER_COLORS[folders.indexOf(f).coerceAtLeast(0) % FOLDER_COLORS.size]
    }

    Box(modifier) {
        val l = lay
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, p, z, _ -> zoom = (zoom * z).coerceIn(0.25f, 4f); pan += p }
                }
                .pointerInput(l, zoom, pan) {
                    detectTapGestures { tap ->
                        val ly = l ?: return@detectTapGestures
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val hit = ly.pos.minByOrNull { (_, w) -> ((center + pan + w * zoom) - tap).getDistanceSquared() }
                        if (hit != null && ((center + pan + hit.value * zoom) - tap).getDistance() < 28.dp.toPx()) onOpen(hit.key)
                    }
                }
        ) {
            val ly = l ?: return@Canvas
            val center = Offset(size.width / 2f, size.height / 2f)
            fun screen(w: Offset) = center + pan + w * zoom
            data.edges.forEach { (a, b) ->
                val pa = ly.pos[a]; val pb = ly.pos[b]
                if (pa != null && pb != null) drawLine(Neon.Cyan.copy(alpha = 0.28f), screen(pa), screen(pb), strokeWidth = 1.5f)
            }
            data.nodes.forEach { (id, title) ->
                val p = ly.pos[id] ?: return@forEach
                val deg = ly.degree[id] ?: 0
                val r = (5f + sqrt(deg.toFloat()) * 4f) * zoom.coerceIn(0.6f, 1.6f)
                val col = colorOf(id)
                val sp = screen(p)
                drawCircle(col.copy(alpha = 0.18f), radius = r * 2.2f, center = sp) // glow
                drawCircle(col, radius = r, center = sp)
                drawCircle(Neon.Void, radius = r, center = sp, style = Stroke(width = 1.5f))
                if (deg > 0 || zoom > 1.3f) {
                    val label = if (title.length > 22) title.take(21) + "…" else title
                    drawText(measurer, label, topLeft = sp + Offset(r + 4f, -r), style = labelStyle)
                }
            }
        }
        if (data.nodes.isEmpty()) Text("no nodes yet — create notes and link them with [[wikilinks]]",
            color = Neon.Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Center))
        else Text("${data.nodes.size} nodes · ${data.edges.size} links · pinch to zoom · tap to open",
            color = Neon.Faint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
    }
}
