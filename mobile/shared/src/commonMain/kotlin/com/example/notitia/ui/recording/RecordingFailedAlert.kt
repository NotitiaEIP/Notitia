package com.example.notitia.ui.recording

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.example.notitia.meeting.RecordingProblem
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.cancel
import notitia.shared.generated.resources.ok
import notitia.shared.generated.resources.recording_busy_message
import notitia.shared.generated.resources.recording_busy_title
import notitia.shared.generated.resources.recording_denied_message
import notitia.shared.generated.resources.recording_denied_title
import notitia.shared.generated.resources.recording_open_settings
import notitia.shared.generated.resources.recording_storage_message
import notitia.shared.generated.resources.recording_storage_title
import notitia.shared.generated.resources.recording_unavailable_message
import notitia.shared.generated.resources.recording_unavailable_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// A recording that could not start waits for an answer, like the system's own recorders: a message that fades away
// could leave someone thinking their meeting is being kept.
@Composable
fun RecordingFailedAlert(problem: RecordingProblem, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val (title, message) = problem.texts()
    val buttons = ButtonDefaults.textButtonColors(contentColor = NotitiaTheme.colors.accentContent)
    val canBeAllowed = problem == RecordingProblem.Denied
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title), style = NotitiaTheme.typography.title3) },
        text = { Text(stringResource(message), style = NotitiaTheme.typography.body) },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canBeAllowed) onOpenSettings()
                    onDismiss()
                },
                colors = buttons,
            ) {
                Text(
                    stringResource(if (canBeAllowed) Res.string.recording_open_settings else Res.string.ok),
                    style = NotitiaTheme.typography.label,
                )
            }
        },
        dismissButton = if (canBeAllowed) {
            {
                TextButton(onClick = onDismiss, colors = buttons) {
                    Text(stringResource(Res.string.cancel), style = NotitiaTheme.typography.label)
                }
            }
        } else {
            null
        },
    )
}

private fun RecordingProblem.texts(): Pair<StringResource, StringResource> = when (this) {
    RecordingProblem.Denied -> Res.string.recording_denied_title to Res.string.recording_denied_message
    RecordingProblem.Busy -> Res.string.recording_busy_title to Res.string.recording_busy_message
    RecordingProblem.Unavailable -> Res.string.recording_unavailable_title to Res.string.recording_unavailable_message
    RecordingProblem.Storage -> Res.string.recording_storage_title to Res.string.recording_storage_message
}

@NotitiaPreviews
@Composable
private fun RecordingFailedAlertPreview() = NotitiaTheme {
    RecordingFailedAlert(RecordingProblem.Denied, onOpenSettings = {}, onDismiss = {})
}

// The longest message, and the only one a phone that keeps its key never shows.
@NotitiaPreviews
@Composable
private fun StorageFailedAlertPreview() = NotitiaTheme {
    RecordingFailedAlert(RecordingProblem.Storage, onOpenSettings = {}, onDismiss = {})
}
