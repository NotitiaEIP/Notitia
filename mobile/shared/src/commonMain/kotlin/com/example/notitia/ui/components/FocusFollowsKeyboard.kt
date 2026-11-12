package com.example.notitia.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager

// Closed by back or a gesture, the keyboard takes the focus with it: no cursor or outline is left without it.
// Derived, so the screen recomposes when the keyboard shows or hides, not on each frame of its slide.
@Composable
fun FocusFollowsKeyboard() {
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    LaunchedEffect(imeVisible) { if (!imeVisible) focusManager.clearFocus() }
}
