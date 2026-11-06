package com.example.notitia.ui.theme

import androidx.compose.runtime.Composable

// The status bar icons follow the phone's theme: when the app forces the other one, they would vanish.
@Composable
expect fun SystemBarsFollow(darkTheme: Boolean)
