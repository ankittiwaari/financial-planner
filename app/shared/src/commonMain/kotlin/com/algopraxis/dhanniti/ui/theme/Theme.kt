package com.algopraxis.dhanniti.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F5C4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4EFD9),
    onPrimaryContainer = Color(0xFF002118),
    secondary = Color(0xFF4C6359),
    tertiary = Color(0xFF8A5A00),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF6FBF7),
    surface = Color(0xFFF6FBF7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF98D3BD),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513E),
    onPrimaryContainer = Color(0xFFB4EFD9),
    secondary = Color(0xFFB3CCC0),
    tertiary = Color(0xFFFFB94D),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF0F1512),
    surface = Color(0xFF0F1512),
)

@Composable
fun DhanNitiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
