package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val SleekColorScheme = darkColorScheme(
    primary = SleekPrimary,
    onPrimary = SleekOnPrimary,
    secondary = SleekSurface,
    onSecondary = SleekOnSurface,
    surface = SleekSurface,
    onSurface = SleekOnSurface,
    background = SleekBackground,
    onBackground = SleekOnSurface,
    surfaceVariant = SleekSurface,
    onSurfaceVariant = SleekOnSurfaceVariant,
    outline = SleekOutline,
    outlineVariant = SleekOutline
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force sleek dark theme by default
  dynamicColor: Boolean = false, // Keep theme branding stable
  content: @Composable () -> Unit,
) {
  val colorScheme = SleekColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
