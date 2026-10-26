package com.example.notitia.ui.theme

import androidx.compose.runtime.Composable

// For animations that loop on their own: Compose does not stop them, and does not read the iOS setting at all.
@Composable
expect fun reducedMotion(): Boolean
