package com.example.notitia.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

// Confirms what was just done and offers to take it back, rather than asking before. A new one replaces the one
// on screen. True when the user chose to take it back.
suspend fun SnackbarHostState.offerUndo(message: String, undoLabel: String): Boolean {
    currentSnackbarData?.dismiss()
    return showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
}
