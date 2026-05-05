package com.kohei.summaryrecorder.ui.theme

import androidx.compose.material3.MaterialTheme
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

private val ColorScheme = lightColorScheme(
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

@Composable
fun SummaryRecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        content = content
    )
}
