package com.kohei.summaryrecorder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Primary = Color(0xFF1976D2)
private val OnPrimary = Color.White
private val PrimaryContainer = Color(0xFFBBDEFB)
private val OnPrimaryContainer = Color(0xFF003258)
private val Secondary = Color(0xFF5D4037)
private val OnSecondary = Color.White
private val Error = Color(0xFFD32F2F)
private val OnError = Color.White
private val ErrorContainer = Color(0xFFFFCDD2)
private val OnErrorContainer = Color(0xFF5F0000)
private val Background = Color(0xFFFFFBFE)
private val OnBackground = Color(0xFF1C1B1F)
private val Surface = Color(0xFFFFFBFE)
private val OnSurface = Color(0xFF1C1B1F)

// Dark theme colors
private val DarkPrimary = Color(0xFF90CAF9)
private val DarkOnPrimary = Color(0xFF003258)
private val DarkPrimaryContainer = Color(0xFF004A8C)
private val DarkOnPrimaryContainer = Color(0xFFBBDEFB)
private val DarkSecondary = Color(0xFFBCAAA4)
private val DarkOnSecondary = Color(0xFF321911)
private val DarkError = Color(0xFFEF9A9A)
private val DarkOnError = Color(0xFF5F0000)
private val DarkErrorContainer = Color(0xFF8C1A1A)
private val DarkOnErrorContainer = Color(0xFFFFCDD2)
private val DarkBackground = Color(0xFF1C1B1F)
private val DarkOnBackground = Color(0xFFE6E1E5)
private val DarkSurface = Color(0xFF1C1B1F)
private val DarkOnSurface = Color(0xFFE6E1E5)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
)

@Composable
fun SummaryRecorderTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
