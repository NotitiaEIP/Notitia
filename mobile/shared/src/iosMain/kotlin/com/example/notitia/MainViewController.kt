package com.example.notitia

import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController

// Compose would slide the whole screen up to show a focused field. The search field places itself on the
// keyboard instead, with imePadding.
fun MainViewController() = ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) { App() }