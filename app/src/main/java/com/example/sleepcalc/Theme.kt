package com.example.sleepcalc

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0A0A0A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color(0xFF0A0A0A),
    secondary = Color(0xFF6B6B6B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color(0xFF0A0A0A),
    background = Color.White,
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFF4F4F4),
    onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFEAEAEA),
    onSurfaceVariant = Color(0xFF6B6B6B),
    outline = Color(0xFFCDCDCD),
    outlineVariant = Color(0xFFE0E0E0),
    surfaceContainerHigh = Color(0xFFF4F4F4),
    surfaceContainerHighest = Color(0xFFE8E8E8)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF2F2F2),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFF2F2F2),
    secondary = Color(0xFFA0A0A0),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFF2F2F2),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFA0A0A0),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF262626)
)

@Composable
fun SleepTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = if (dark) DarkScheme else LightScheme

    // Цвет статус-бара и панели навигации в цвет фона
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
