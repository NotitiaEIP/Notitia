package com.example.notitia.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

enum class ThemeChoice { System, Light, Dark }

@Composable
fun ThemeChoice.isDark(): Boolean = when (this) {
    ThemeChoice.System -> isSystemInDarkTheme()
    ThemeChoice.Light -> false
    ThemeChoice.Dark -> true
}
