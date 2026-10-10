package com.hermitech.cortexboard.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermitech.cortexboard.ui.theme.Neon

val PanelShape = CutCornerShape(topStart = 0.dp, topEnd = 12.dp, bottomEnd = 0.dp, bottomStart = 12.dp)
val ChipShape = CutCornerShape(6.dp)

/** Faint perspective-free grid + top glow: the cyber backdrop for every screen. */
@Composable
fun CyberBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Neon.Void)
            .drawBehind {
                val step = 28.dp.toPx()
                var x = 0f
                while (x < size.width) { drawLine(Neon.Grid, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
                var y = 0f
                while (y < size.height) { drawLine(Neon.Grid, Offset(0f, y), Offset(size.width, y), 1f); y += step }
                drawRect(Brush.verticalGradient(listOf(Neon.Cyan.copy(alpha = 0.10f), Color.Transparent), endY = size.height * 0.35f))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Neon.Magenta.copy(alpha = 0.06f)), startY = size.height * 0.7f))
            },
        content = content,
    )
}

@Composable
fun NeonPanel(
    modifier: Modifier = Modifier,
    accent: Color = Neon.Cyan,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(Neon.Panel.copy(alpha = 0.92f), PanelShape)
            .border(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.15f))), PanelShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        content = content,
    )
}

@Composable
fun SectionHeader(text: String, accent: Color = Neon.Cyan, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("//", color = accent.copy(alpha = 0.6f), style = MaterialTheme.typography.titleSmall)
        Text(
            " $text", color = accent, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

@Composable
fun NeonChip(text: String, selected: Boolean = false, accent: Color = Neon.Cyan, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 6.dp)
            .background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent, ChipShape)
            .border(1.dp, if (selected) accent else Neon.Faint, ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, color = if (selected) accent else Neon.Dim, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun NeonButton(text: String, accent: Color = Neon.Cyan, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.35f)
            .background(accent.copy(alpha = 0.12f), ChipShape)
            .border(1.dp, accent, ChipShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = accent, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun TermField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    accent: Color = Neon.Cyan,
    minLines: Int = 1,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    onSubmit: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = textStyle,
        placeholder = { Text(placeholder, color = Neon.Dim, style = textStyle) },
        shape = ChipShape,
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null && singleLine) ImeAction.Send else ImeAction.Default),
        keyboardActions = KeyboardActions(onSend = { onSubmit?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent, unfocusedBorderColor = Neon.Faint,
            cursorColor = accent, focusedTextColor = Neon.Text, unfocusedTextColor = Neon.Text,
            focusedContainerColor = Neon.Panel.copy(alpha = 0.9f), unfocusedContainerColor = Neon.Panel.copy(alpha = 0.7f),
        ),
    )
}

@Composable
fun PulseDot(color: Color, active: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(8.dp).alpha(if (active) a else 1f).background(color, CircleShape))
}

@Composable
fun Glyph(text: String, color: Color = Neon.Cyan, onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick).padding(8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Neon.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, color = Neon.Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodySmall)
    }
}
