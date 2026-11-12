package com.example.notitia.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.cancel
import org.jetbrains.compose.resources.stringResource

// Material's dialog, as in Drive and Gmail: no icon, since the title already says what goes; « Annuler » then the
// red verb, side by side at the end.
@Composable
actual fun DestructiveConfirmation(
    destruction: Destruction?,
    anchor: Rect?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    destruction ?: return
    val colors = NotitiaTheme.colors
    val typography = NotitiaTheme.typography
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(destruction.title, style = typography.title3) },
        text = { Text(destruction.message, style = typography.body) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = colors.red)) {
                Text(destruction.shortConfirm, style = typography.label)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = colors.accentContent)) {
                Text(stringResource(Res.string.cancel), style = typography.label)
            }
        },
    )
}

@NotitiaPreviews
@Composable
private fun DestructiveConfirmationPreview() = NotitiaTheme {
    DestructiveConfirmation(
        destruction = Destruction(
            title = "Supprimer « Point produit » ?",
            message = "Sa transcription, son compte rendu et ses actions seront effacés de ce téléphone.",
            confirm = "Supprimer la réunion",
            shortConfirm = "Supprimer",
        ),
        anchor = null,
        onConfirm = {},
        onDismiss = {},
    )
}
