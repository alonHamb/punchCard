package com.punchcard.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BrandBg = Color(0xFF0F172A)
val BrandCard = Color(0xFFFFFFFF)
val BrandAccent = Color(0xFF2563EB)
val BrandStart = Color(0xFF16A34A)
val BrandStartDark = Color(0xFF15803D)
val BrandEnd = Color(0xFFEA580C)
val BrandEndDark = Color(0xFFC2410C)
val BrandMuted = Color(0xFF64748B)
val BrandDanger = Color(0xFFDC2626)
val BrandTextOnCard = Color(0xFF0F172A)

private val AppColorScheme = darkColorScheme(
    primary = BrandAccent,
    background = BrandBg,
    surface = BrandBg,
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun PunchCardTheme(content: @Composable () -> Unit) {
    // Deliberately not following system dark/light — the app always uses
    // its own dark-navy + white-card look, same as the original design.
    MaterialTheme(
        colorScheme = AppColorScheme,
        content = content,
    )
}
