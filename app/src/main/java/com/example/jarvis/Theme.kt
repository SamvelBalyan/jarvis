package com.example.jarvis

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object J {
    val bg = Color(0xFF0A0F1E)
    val card = Color(0xFF131B31)
    val cardHi = Color(0xFF1B2646)
    val cyan = Color(0xFF5BC8FF)
    val violet = Color(0xFF8B7BFF)
    val pink = Color(0xFFFF7AD9)
    val text = Color(0xFFE8EEFF)
    val dim = Color(0xFF8FA0C4)
    val good = Color(0xFF4ADE80)
    val bad = Color(0xFFFF6B6B)
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = J.cyan,
            onPrimary = Color(0xFF00212E),
            secondary = J.violet,
            background = J.bg,
            surface = J.card,
            onSurface = J.text,
            onBackground = J.text,
            surfaceVariant = J.cardHi,
            onSurfaceVariant = J.dim,
            outline = Color(0xFF3A4A75)
        ),
        content = content
    )
}
