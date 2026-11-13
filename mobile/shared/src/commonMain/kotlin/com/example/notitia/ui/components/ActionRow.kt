package com.example.notitia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.ui.meetings.sampleMinutes
import com.example.notitia.ui.meetings.shortDateLabel
import com.example.notitia.ui.minutes.people
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import kotlin.time.Clock
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.action_due
import notitia.shared.generated.resources.action_no_owner
import org.jetbrains.compose.resources.stringResource

// An action of the minutes. The whole row checks it, so the target is large and screen readers read it in one go:
// "Préparer la démo, Clément, avant le 8 nov., case à cocher, non cochée".
// speaker is the owner's colour in the meeting.
@Composable
fun ActionRow(action: MinutesAction, onToggle: () -> Unit, modifier: Modifier = Modifier, speaker: Int? = null) {
    val spacing = NotitiaTheme.spacing
    val details = buildList {
        add(action.owner ?: stringResource(Res.string.action_no_owner))
        action.due?.let { add(stringResource(Res.string.action_due, shortDateLabel(it))) }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NotitiaTheme.sizes.touchTarget)
            .toggleable(value = action.done, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = spacing.s),
        horizontalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        ActionCheck(action.done)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            StrikeText(text = action.text, struck = action.done, style = NotitiaTheme.typography.body)
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (speaker != null) SpeakerDot(speaker)
                DetailsLine(details, style = NotitiaTheme.typography.caption)
            }
        }
    }
}

@NotitiaPreviews
@Composable
private fun ActionRowPreview() = NotitiaTheme {
    val now = Clock.System.now()
    val minutes = sampleMinutes(now).first()
    Column(Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l)) {
        val people = minutes.people
        minutes.actions.forEach { action ->
            ActionRow(action = action, onToggle = {}, speaker = action.owner?.let(people::of))
        }
    }
}
