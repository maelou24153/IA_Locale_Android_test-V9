package com.iastudio.vm

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Terracotta = Color(0xFFD97757)

private val LightColors = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1E3DA),
    onPrimaryContainer = Color(0xFF3A281F),
    background = Color(0xFFF5F4ED),
    onBackground = Color(0xFF1F1E1D),
    surface = Color(0xFFFAF9F5),
    onSurface = Color(0xFF1F1E1D),
    surfaceVariant = Color(0xFFECE9DF),
    onSurfaceVariant = Color(0xFF5F5C55),
    outline = Color(0xFFD6D2C4),
)

private val DarkColors = darkColorScheme(
    primary = Terracotta,
    onPrimary = Color(0xFF1F1E1D),
    primaryContainer = Color(0xFF4A3429),
    onPrimaryContainer = Color(0xFFF5E6DC),
    background = Color(0xFF262624),
    onBackground = Color(0xFFF2F0E8),
    surface = Color(0xFF30302E),
    onSurface = Color(0xFFF2F0E8),
    surfaceVariant = Color(0xFF3A3936),
    onSurfaceVariant = Color(0xFFB9B5A9),
    outline = Color(0xFF55524B),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 38.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
