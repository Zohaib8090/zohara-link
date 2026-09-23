package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BentoLightColorScheme = lightColorScheme(
    primary = BentoPrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = BentoSoftBlue,
    onPrimaryContainer = BentoDarkNavy,
    secondary = BentoDarkNavy,
    onSecondary = Color.White,
    secondaryContainer = BentoSlate,
    onSecondaryContainer = BentoTextPrimary,
    tertiary = BentoPrimaryBlue,
    onTertiary = Color.White,
    background = BentoBg,
    onBackground = BentoTextBody,
    surface = BentoCardWhite,
    onSurface = BentoTextBody,
    surfaceVariant = BentoSlate,
    onSurfaceVariant = BentoTextMuted,
    outline = BentoBorder,
    outlineVariant = BentoSlate,
    error = BentoRed,
    onError = Color.White
)

private val BentoDarkColorScheme = darkColorScheme(
    primary = BentoPrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = BentoDarkNavy,
    onPrimaryContainer = BentoSoftBlue,
    secondary = BentoSoftBlue,
    onSecondary = BentoDarkNavy,
    secondaryContainer = BentoDarkNavy,
    onSecondaryContainer = BentoSoftBlue,
    tertiary = BentoPrimaryBlue,
    onTertiary = Color.White,
    background = BentoDarkNavy,
    onBackground = Color.White,
    surface = Color(0xFF072146),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF102D5A),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF26497B),
    error = BentoRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false, // Default to crisp Bento Grid theme
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) BentoDarkColorScheme else BentoLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

