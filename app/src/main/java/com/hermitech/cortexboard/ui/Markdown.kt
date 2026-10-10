package com.hermitech.cortexboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hermitech.cortexboard.ui.theme.Neon

/**
 * Deliberately small markdown renderer: headings, bullets, checkboxes, quotes, code fences,
 * **bold**, *italic*, `code`, #tags and clickable [[wikilinks]].
 */
@Composable
fun MarkdownView(text: String, onLink: (String) -> Unit, onToggleCheck: ((Int) -> Unit)? = null) {
    val lines = text.lines()
    var inCode = false
    Column(Modifier.fillMaxWidth()) {
        lines.forEachIndexed { idx, raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) { inCode = !inCode; return@forEachIndexed }
            if (inCode) {
                Text(line, style = MaterialTheme.typography.bodySmall, color = Neon.Lime,
                    modifier = Modifier.fillMaxWidth().background(Neon.Void).padding(horizontal = 8.dp, vertical = 1.dp))
                return@forEachIndexed
            }
            when {
                line.isBlank() -> Box(Modifier.height(8.dp))
                line.startsWith("# ") -> Text(inline(line.drop(2), onLink), style = MaterialTheme.typography.headlineSmall, color = Neon.Cyan, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                line.startsWith("## ") -> Text(inline(line.drop(3), onLink), style = MaterialTheme.typography.titleMedium, color = Neon.Magenta, modifier = Modifier.padding(top = 6.dp))
                line.startsWith("### ") -> Text(inline(line.drop(4), onLink), style = MaterialTheme.typography.titleSmall, color = Neon.Amber, modifier = Modifier.padding(top = 4.dp))
                line == "---" -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Neon.Faint))
                CHECK.matches(line) -> {
                    val m = CHECK.find(line)!!
                    val done = m.groupValues[2].isNotBlank()
                    Row(Modifier.padding(start = (m.groupValues[1].length * 6).dp, top = 1.dp)) {
                        Text(if (done) "▣ " else "□ ", color = if (done) Neon.Lime else Neon.Cyan,
                            modifier = if (onToggleCheck != null) Modifier.clickable { onToggleCheck(idx) } else Modifier)
                        Text(inline(m.groupValues[3], onLink), style = MaterialTheme.typography.bodyMedium,
                            color = if (done) Neon.Dim else Neon.Text,
                            textDecoration = if (done) TextDecoration.LineThrough else null)
                    }
                }
                BULLET.matches(line) -> {
                    val m = BULLET.find(line)!!
                    Row(Modifier.padding(start = (m.groupValues[1].length * 6).dp, top = 1.dp)) {
                        Text("▸ ", color = Neon.Cyan)
                        Text(inline(m.groupValues[2], onLink), style = MaterialTheme.typography.bodyMedium, color = Neon.Text)
                    }
                }
                line.startsWith(">") -> Row(Modifier.padding(vertical = 2.dp)) {
                    Box(Modifier.width(2.dp).height(18.dp).background(Neon.Magenta))
                    Text(inline(line.drop(1).trim(), onLink), style = MaterialTheme.typography.bodyMedium, color = Neon.Dim,
                        fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 8.dp))
                }
                else -> Text(inline(line, onLink), style = MaterialTheme.typography.bodyMedium, color = Neon.Text)
            }
        }
    }
}

private val CHECK = Regex("""^(\s*)[-*] \[( |x|X)] (.*)$""")
private val BULLET = Regex("""^(\s*)(?:[-*]|\d+\.) (.*)$""")
private val INLINE = Regex("""\[\[([^\]|#]+)(?:[#|]([^\]]*))?]]|\*\*(.+?)\*\*|`([^`]+)`|\*(.+?)\*|(?<![\w/])#([A-Za-z][\w/-]+)""")

fun inline(s: String, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> {
                val target = g[1].trim()
                val label = g[2].ifBlank { target }
                withLink(LinkAnnotation.Clickable(target, TextLinkStyles(SpanStyle(color = Neon.Cyan, fontWeight = FontWeight.Bold))) { onLink(target) }) {
                    append("⟦$label⟧")
                }
            }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(color = Neon.Lime, background = Neon.Void)) { append(g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(color = Neon.Magenta)) { append("#" + g[6]) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

fun toggleCheckbox(body: String, lineIndex: Int): String {
    val lines = body.lines().toMutableList()
    if (lineIndex !in lines.indices) return body
    val l = lines[lineIndex]
    lines[lineIndex] = when {
        l.contains("- [ ] ") -> l.replaceFirst("- [ ] ", "- [x] ")
        l.contains("* [ ] ") -> l.replaceFirst("* [ ] ", "* [x] ")
        else -> l.replaceFirst(Regex("""([-*]) \[[xX]] """), "$1 [ ] ")
    }
    return lines.joinToString("\n")
}
