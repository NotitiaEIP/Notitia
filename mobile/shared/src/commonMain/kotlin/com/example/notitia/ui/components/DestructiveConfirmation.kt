package com.example.notitia.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect

// In the user's words, never « OK ». The iPhone's sheet spans the width and says the whole action, « Supprimer la
// réunion »; Android's two buttons share a line, which only the verb leaves them, « Supprimer ».
data class Destruction(val title: String, val message: String, val confirm: String, val shortConfirm: String)

// Asks before what cannot be taken back, the way each system does: the action sheet on iPhone, which grows out of
// anchor (the button that asked, in window pixels) since iOS 26, and the dialog on Android. Up while destruction is
// set; the caller clears it on either answer.
@Composable
expect fun DestructiveConfirmation(
    destruction: Destruction?,
    anchor: Rect?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
)
