package com.mindfulscroll.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = MossGreen,
    onPrimary = MistWhite,
    secondary = SageGreen,
    onSecondary = InkGray,
    background = WarmSand,
    onBackground = InkGray,
    surface = MistWhite,
    onSurface = InkGray,
    error = FrictionRed,
)

private val DarkColors = darkColorScheme(
    primary = SageGreen,
    onPrimary = DeepGreen,
    secondary = MossGreen,
    onSecondary = MistWhite,
    background = InkGray,
    onBackground = MistWhite,
    surface = DeepGreen,
    onSurface = MistWhite,
    error = FrictionRed,
)

@Composable
fun MindfulScrollTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = MindfulScrollTypography,
        content = content,
    )
}
