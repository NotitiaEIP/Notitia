package com.example.notitia.ui.actions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.example.notitia.ui.components.ActionCheck
import com.example.notitia.ui.components.DetailsLine
import com.example.notitia.ui.components.NotitiaCard
import com.example.notitia.ui.components.SpeakerAvatar
import com.example.notitia.ui.components.StrikeText
import com.example.notitia.ui.components.SwipeableAction
import com.example.notitia.ui.meetings.shortDateLabel
import com.example.notitia.ui.minutes.MinutesAction
import com.example.notitia.ui.theme.NotitiaTheme
import kotlinx.datetime.LocalDate
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.action_due
import notitia.shared.generated.resources.action_due_today
import notitia.shared.generated.resources.action_due_tomorrow
import notitia.shared.generated.resources.action_late_by
import notitia.shared.generated.resources.action_mark_done
import notitia.shared.generated.resources.action_mark_todo
import notitia.shared.generated.resources.action_no_owner
import org.jetbrains.compose.resources.stringResource

private const val MAX_LINES = 3

// The tick is a button of its own; the rest of the card opens the action's sheet. Screen readers
// read the card in one go and tick it through its action, as they cannot swipe. A swipe completes it at once:
// the card is already gone, so it does not wait like a tick does.
@Composable
fun ActionCard(
    tracked: TrackedAction,
    today: LocalDate,
    onToggle: () -> Unit,
    onSwipedDone: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = tracked.action
    val spacing = NotitiaTheme.spacing
    val toggleLabel = stringResource(if (action.done) Res.string.action_mark_todo else Res.string.action_mark_done)
    SwipeableAction(enabled = !action.done, onDone = onSwipedDone, modifier = modifier) {
        NotitiaCard(
            onClick = onOpen,
            modifier = Modifier.semantics {
                customActions = listOf(CustomAccessibilityAction(toggleLabel) { onToggle(); true })
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
                CheckButton(action.done, onToggle)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.s)) {
                    StrikeText(
                        text = action.text,
                        struck = action.done,
                        style = NotitiaTheme.typography.body,
                        maxLines = MAX_LINES,
                    )
                    OwnerAndDue(tracked, today)
                }
            }
        }
    }
}

// Centred on the first line of the text. Its target reaches into the card's margin without pushing anything.
@Composable
internal fun CheckButton(done: Boolean, onToggle: () -> Unit) {
    val sizes = NotitiaTheme.sizes
    val firstLine = with(LocalDensity.current) { NotitiaTheme.typography.body.lineHeight.toDp() }
    Box(Modifier.size(sizes.icon, firstLine), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .requiredSize(sizes.checkTarget, sizes.touchTarget)
                .clearAndSetSemantics {}
                .clip(NotitiaTheme.shapes.full)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            ActionCheck(done)
        }
    }
}

// "demain" or "avant le 3 nov.", or, while it is not done and past its date, only late: "2 j de retard".
internal data class DueWords(val due: String?, val late: String?)

@Composable
internal fun dueWords(action: MinutesAction, today: LocalDate): DueWords {
    val group = action.due?.let { dueGroup(it, today) }
    val late = action.due
        ?.takeIf { !action.done && group == DueGroup.Late }
        ?.let { stringResource(Res.string.action_late_by, daysLate(it, today)) }
    val due = action.due?.takeIf { late == null }?.let { due ->
        when (group) {
            DueGroup.Today -> stringResource(Res.string.action_due_today)
            DueGroup.Tomorrow -> stringResource(Res.string.action_due_tomorrow)
            else -> stringResource(Res.string.action_due, shortDateLabel(due))
        }
    }
    return DueWords(due, late)
}

// "Clément · demain", or "Nathan · 2 j de retard" in red while it is not done.
@Composable
private fun OwnerAndDue(tracked: TrackedAction, today: LocalDate) {
    val action = tracked.action
    val (due, late) = dueWords(action, today)
    Row(
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        action.owner?.let { SpeakerAvatar(it, tracked.speaker) }
        DetailsLine(
            parts = listOfNotNull(action.owner ?: stringResource(Res.string.action_no_owner), due),
            style = NotitiaTheme.typography.caption,
            alert = late,
        )
    }
}
