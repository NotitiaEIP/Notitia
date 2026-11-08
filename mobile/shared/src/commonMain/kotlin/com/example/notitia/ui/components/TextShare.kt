package com.example.notitia.ui.components

import androidx.compose.runtime.Composable

// Hands a text to the system's share sheet: the user picks the app, Notitia sends nothing itself (product rule 1).
// onShared comes once the text is on its way: on iPhone only when it was sent, on Android as soon as the chooser
// opens, since Android does not tell.
@Composable
expect fun rememberTextShare(): (text: String, onShared: () -> Unit) -> Unit
