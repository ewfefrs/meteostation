package com.example.meteo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF0B2545)
val Teal = Color(0xFF1B998B)
val Orange = Color(0xFFE07A2E)
val Ice = Color(0xFFEEF4F8)
val Muted = Color(0xFF5B6B7B)
val Blue = Color(0xFF4F83B8)

private val scheme = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    secondary = Teal,
    onSecondary = Color.White,
    tertiary = Orange,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Ice,
    onSurfaceVariant = Muted,
    primaryContainer = Ice,
    onPrimaryContainer = Navy,
    secondaryContainer = Color(0xFFD5F0EC),
)

@Composable
fun MeteoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
