package com.example.notitia.ui.actions

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.notitia.ui.components.CardChevron
import com.example.notitia.ui.components.DetailsLine
import com.example.notitia.ui.components.StrikeText
import com.example.notitia.ui.meetings.sampleMeetings
import com.example.notitia.ui.minutes.sampleMinutes
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import kotlin.time.Clock
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.action_missing_due
import notitia.shared.generated.resources.action_missing_owner
import notitia.shared.generated.resources.actions_to_complete
import notitia.shared.generated.resources.edit_note
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val MAX_LINES = 2

// The open actions nobody can follow yet, with what each one lacks. A line opens the action's sheet; once complete,
// the action leaves for its group and the card shrinks around the others.
@Composable
fun ToCompleteCard(actions: List<TrackedAction>, onOpen: (TrackedAction) -> Unit, modifier: Modifier = Modifier) {
    val colors = NotitiaTheme.colors
    val spacing = NotitiaTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(NotitiaTheme.shapes.card)
            // Tinted over a card rather than the screen: amber text keeps its contrast in light.
            .background(colors.surface)
            .background(colors.amberSoft)
            .then(if (reducedMotion()) Modifier else Modifier.animateContentSize(NotitiaTheme.motion.standard())),
    ) {
        Row(
            modifier = Modifier
                .padding(start = spacing.l, top = spacing.m, end = spacing.l, bottom = spacing.xs)
                .semantics(mergeDescendants = true) { heading() },
            horizontalArrangement = Arrangement.spacedBy(spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.edit_note),
                contentDescription = null,
                tint = colors.amber,
                modifier = Modifier.size(NotitiaTheme.sizes.icon),
            )
            Text(
                text = stringResource(Res.string.actions_to_complete),
                style = NotitiaTheme.typography.labelSmall,
                color = colors.amber,
            )
        }
        actions.forEachIndexed { index, tracked ->
            key(tracked.id.key) {
                if (index > 0) HorizontalDivider(Modifier.padding(start = spacing.l), color = colors.divider)
                ToCompleteRow(tracked, onOpen = { onOpen(tracked) })
            }
        }
    }
}

// "Qui ?", "Pour quand ?" or both, in amber, under the action.
@Composable
private fun ToCompleteRow(tracked: TrackedAction, onOpen: () -> Unit) {
    val action = tracked.action
    val spacing = NotitiaTheme.spacing
    val missing = listOfNotNull(
        stringResource(Res.string.action_missing_owner).takeIf { action.owner == null },
        stringResource(Res.string.action_missing_due).takeIf { action.due == null },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NotitiaTheme.sizes.touchTarget)
            .clickable(onClick = onOpen)
            .padding(horizontal = spacing.l, vertical = spacing.m),
        horizontalArrangement = Arrangement.spacedBy(spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            StrikeText(
                text = action.text,
                struck = action.done,
                style = NotitiaTheme.typography.body,
                maxLines = MAX_LINES,
            )
            DetailsLine(missing, style = NotitiaTheme.typography.caption, color = NotitiaTheme.colors.amber)
        }
        CardChevron()
    }
}

@NotitiaPreviews
@Composable
private fun ToCompleteCardPreview() = NotitiaTheme {
    val now = Clock.System.now()
    val actions = ActionsState().tracked(sampleMeetings(now).map { sampleMinutes(it, now) })
    ToCompleteCard(
        actions = actions.filter { !it.action.done && (it.action.owner == null || it.action.due == null) },
        onOpen = {},
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
    )
}
