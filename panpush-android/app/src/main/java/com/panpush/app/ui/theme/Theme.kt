package com.panpush.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val PanBlue = Color(0xFF4C8DFF)
val PanGreen = Color(0xFF8FE3B0)
val PanAmber = Color(0xFFFFC46B)
val PanRed = Color(0xFFFF6B6B)

private val DarkScheme = darkColorScheme(
    primary = PanBlue,
    onPrimary = Color(0xFF07131F),
    primaryContainer = Color(0xFF14304F),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = PanGreen,
    onSecondary = Color(0xFF08210F),
    tertiary = PanAmber,
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE7EBF2),
    surface = Color(0xFF121620),
    onSurface = Color(0xFFE7EBF2),
    surfaceVariant = Color(0xFF1B2130),
    onSurfaceVariant = Color(0xFFB6BFD0),
    outline = Color(0xFF39415A),
    error = PanRed
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F5FD0),
    secondary = Color(0xFF1B7A48),
    tertiary = Color(0xFF9A6400),
    background = Color(0xFFF6F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6EAF2),
    outline = Color(0xFFB9C0D0)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
)

@Composable
fun PanPushTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            (context as? Activity)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
