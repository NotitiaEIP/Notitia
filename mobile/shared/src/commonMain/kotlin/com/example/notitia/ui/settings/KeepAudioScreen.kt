package com.example.notitia.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.example.notitia.meeting.AudioKeeping
import com.example.notitia.meeting.KeepAudio
import com.example.notitia.meeting.PreviewAudioKeeping
import com.example.notitia.ui.components.Destruction
import com.example.notitia.ui.components.DestructiveConfirmation
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.settings
import notitia.shared.generated.resources.settings_back_description
import notitia.shared.generated.resources.settings_keep_audio
import notitia.shared.generated.resources.settings_keep_audio_day
import notitia.shared.generated.resources.settings_keep_audio_erase
import notitia.shared.generated.resources.settings_keep_audio_erase_confirm
import notitia.shared.generated.resources.settings_keep_audio_erase_day
import notitia.shared.generated.resources.settings_keep_audio_erase_message
import notitia.shared.generated.resources.settings_keep_audio_erase_until_heard
import notitia.shared.generated.resources.settings_keep_audio_erase_week
import notitia.shared.generated.resources.settings_keep_audio_footer
import notitia.shared.generated.resources.settings_keep_audio_month
import notitia.shared.generated.resources.settings_keep_audio_until_heard
import notitia.shared.generated.resources.settings_keep_audio_week
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// As « Conserver les messages » in the iPhone's Messages: the choices and a check, nothing to save. chosen is the
// core's, null until it has said, and the rows wait for it.
@Composable
fun KeepAudioScreen(
    chosen: KeepAudio?,
    keeping: AudioKeeping,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val choice = remember(keeping) { KeepAudioChoice(keeping) }
    // Read only when a row is touched, so a layout writes here without recomposing anything.
    val rows = remember { mutableMapOf<KeepAudio, Rect>() }
    SettingsFrame(
        back = stringResource(Res.string.settings),
        backDescription = stringResource(Res.string.settings_back_description),
        title = stringResource(Res.string.settings_keep_audio),
        onBack = onBack,
        modifier = modifier,
    ) {
        SettingsGroup(
            title = null,
            modifier = Modifier.selectableGroup(),
            footer = stringResource(Res.string.settings_keep_audio_footer),
        ) {
            KeepAudio.entries.forEachIndexed { index, keep ->
                if (index > 0) RowDivider()
                ChoiceRow(
                    label = stringResource(keep.label),
                    selected = keep == chosen,
                    modifier = Modifier.onGloballyPositioned { rows[keep] = it.boundsInWindow() },
                    enabled = chosen != null,
                    onClick = { choice.choose(keep, rows[keep]) },
                )
            }
        }
    }
    val asked = choice.asked
    DestructiveConfirmation(
        destruction = asked?.let { keepAudioErasure(it.choice) },
        anchor = asked?.anchor,
        onConfirm = choice::confirm,
        onDismiss = choice::cancel,
    )
}

internal val KeepAudio.label: StringResource
    get() = when (this) {
        KeepAudio.UntilHeard -> Res.string.settings_keep_audio_until_heard
        KeepAudio.Day -> Res.string.settings_keep_audio_day
        KeepAudio.Week -> Res.string.settings_keep_audio_week
        KeepAudio.Month -> Res.string.settings_keep_audio_month
    }

// Month is the longest choice: never shorter than another, it erases nothing to ask about.
@Composable
private fun keepAudioErasure(choice: KeepAudio): Destruction? {
    val title = when (choice) {
        KeepAudio.UntilHeard -> Res.string.settings_keep_audio_erase_until_heard
        KeepAudio.Day -> Res.string.settings_keep_audio_erase_day
        KeepAudio.Week -> Res.string.settings_keep_audio_erase_week
        KeepAudio.Month -> return null
    }
    return Destruction(
        title = stringResource(title),
        message = stringResource(Res.string.settings_keep_audio_erase_message),
        confirm = stringResource(Res.string.settings_keep_audio_erase_confirm),
        shortConfirm = stringResource(Res.string.settings_keep_audio_erase),
    )
}

@NotitiaPreviews
@Composable
private fun KeepAudioScreenPreview() = NotitiaTheme {
    val keeping = remember { PreviewAudioKeeping(KeepAudio.Week) }
    val chosen by keeping.keepAudio.collectAsState()
    KeepAudioScreen(
        chosen = chosen,
        keeping = keeping,
        onBack = {},
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}
