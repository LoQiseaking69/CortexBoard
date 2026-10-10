package com.hermitech.cortexboard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Neon {
    val Void = Color(0xFF040609)
    val Panel = Color(0xFF0A0F16)
    val PanelHi = Color(0xFF111A24)
    val Grid = Color(0xFF0E1E28)
    val Cyan = Color(0xFF00F0FF)
    val Magenta = Color(0xFFFF2BD6)
    val Lime = Color(0xFF39FF88)
    val Amber = Color(0xFFFFB000)
    val Red = Color(0xFFFF3B5C)
    val Text = Color(0xFFCDF8FF)
    val Dim = Color(0xFF5B7C88)
    val Faint = Color(0xFF26404A)
}

val Mono = FontFamily.Monospace

private val CyberTypography = Typography(
    displaySmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 3.sp),
    headlineSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 2.sp),
    titleMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.5.sp),
    titleSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.sp),
    bodyLarge = TextStyle(fontFamily = Mono, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.5.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp),
)

@Composable
fun CortexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon.Cyan, onPrimary = Neon.Void,
            secondary = Neon.Magenta, onSecondary = Neon.Void,
            tertiary = Neon.Lime,
            background = Neon.Void, onBackground = Neon.Text,
            surface = Neon.Panel, onSurface = Neon.Text,
            surfaceVariant = Neon.PanelHi, onSurfaceVariant = Neon.Dim,
            surfaceContainer = Neon.Panel, surfaceContainerHigh = Neon.PanelHi,
            outline = Neon.Faint, error = Neon.Red,
        ),
        typography = CyberTypography,
        content = content,
    )
}
