package com.example.notitia.ui.actions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.example.notitia.ui.components.DetailsLine
import com.example.notitia.ui.components.DoneRing
import com.example.notitia.ui.components.SpeakerAvatar
import com.example.notitia.ui.components.StrikeText
import com.example.notitia.ui.components.cardShadow
import com.example.notitia.ui.meetings.sampleMinutes
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.action_mark_done
import notitia.shared.generated.resources.action_mark_todo
import notitia.shared.generated.resources.action_missing_due
import notitia.shared.generated.resources.action_no_owner
import notitia.shared.generated.resources.actions_late
import notitia.shared.generated.resources.actions_nothing_open
import notitia.shared.generated.resources.actions_open
import notitia.shared.generated.resources.chat_bubble
import notitia.shared.generated.resources.keyboard_arrow_down
import notitia.shared.generated.resources.nudge_title
import notitia.shared.generated.resources.person
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val HALF_TURN = 180f
private const val MAX_LINES = 2

// One owner, folded on what they have on, unfolded on their open actions, as the groups of Reminders. The header
// unfolds the card; a line opens the action's sheet and its circle ticks it, as on an action card. No swipe in
// here: a line is not a card, and a swipe would fight the list's scroll on a short row. Under the lines, a link
// nudges the person about all of them; onNudge is null when there is nobody to nudge.
@Composable
fun PersonCard(
    person: PersonActions,
    today: LocalDate,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onToggle: (TrackedAction) -> Unit,
    onOpen: (TrackedAction) -> Unit,
    onNudge: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = NotitiaTheme.colors
    val spacing = NotitiaTheme.spacing
    val unfolded = expanded && person.open.isNotEmpty()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .cardShadow(NotitiaTheme.shapes.card)
            .background(colors.surface),
    ) {
        PersonHeader(person, unfolded, onExpandedChange)
        AnimatedVisibility(
            visible = unfolded,
            enter = foldEnter(reducedMotion()),
            exit = foldExit(reducedMotion()),
        ) {
            Column {
                person.open.forEach { tracked ->
                    key(tracked.id.key) {
                        HorizontalDivider(Modifier.padding(start = spacing.l), color = colors.divider)
                        PersonActionRow(tracked, today, onToggle = { onToggle(tracked) }, onOpen = { onOpen(tracked) })
                    }
                }
                val name = person.name
                if (name != null && onNudge != null) {
                    HorizontalDivider(Modifier.padding(start = spacing.l), color = colors.divider)
                    NudgeRow(name, onNudge)
                }
            }
        }
    }
}

// "Nathan", "3 en cours · 1 en retard", then "2/5" and the ring. Nothing to unfold when nothing is open.
@Composable
private fun PersonHeader(person: PersonActions, expanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
    val colors = NotitiaTheme.colors
    val spacing = NotitiaTheme.spacing
    val foldable = person.open.isNotEmpty()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) HALF_TURN else 0f,
        animationSpec = if (reducedMotion()) snap() else NotitiaTheme.motion.standard(),
        label = "chevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NotitiaTheme.sizes.touchTarget)
            .then(if (foldable) Modifier.clickable { onExpandedChange(!expanded) } else Modifier)
            .semantics(mergeDescendants = true) {
                heading()
                if (foldable) {
                    if (expanded) collapse { onExpandedChange(false); true } else expand { onExpandedChange(true); true }
                }
            }
            .padding(spacing.l),
        horizontalArrangement = Arrangement.spacedBy(spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonAvatar(person)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                text = person.name ?: stringResource(Res.string.action_no_owner),
                style = NotitiaTheme.typography.bodyStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DetailsLine(
                parts = listOf(
                    if (person.open.isEmpty()) {
                        stringResource(Res.string.actions_nothing_open)
                    } else {
                        stringResource(Res.string.actions_open, person.open.size)
                    },
                ),
                style = NotitiaTheme.typography.caption,
                alert = person.late.takeIf { it > 0 }?.let { stringResource(Res.string.actions_late, it) },
            )
        }
        DoneRing(person.done, person.total)
        // The chevron's place stays, so every ring lines up whether the card unfolds or not.
        if (foldable) {
            Icon(
                painter = painterResource(Res.drawable.keyboard_arrow_down),
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(NotitiaTheme.sizes.icon).rotate(rotation),
            )
        } else {
            Spacer(Modifier.size(NotitiaTheme.sizes.icon))
        }
    }
}

// The person's colour, with their initials; a plain silhouette for the actions nobody owns.
@Composable
private fun PersonAvatar(person: PersonActions) {
    val sizes = NotitiaTheme.sizes
    val name = person.name
    if (name != null) {
        SpeakerAvatar(name, Modifier.size(sizes.personAvatar))
    } else {
        Box(
            modifier = Modifier.size(sizes.personAvatar).background(NotitiaTheme.colors.track, NotitiaTheme.shapes.full),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.person),
                contentDescription = null,
                tint = NotitiaTheme.colors.textSecondary,
                modifier = Modifier.size(sizes.iconInButton),
            )
        }
    }
}

// The circle sits under the avatar, the text under the name. Under it, when it is due, or "Pour quand ?" in amber.
@Composable
private fun PersonActionRow(tracked: TrackedAction, today: LocalDate, onToggle: () -> Unit, onOpen: () -> Unit) {
    val action = tracked.action
    val spacing = NotitiaTheme.spacing
    val colors = NotitiaTheme.colors
    val (due, late) = dueWords(action, today)
    val toggleLabel = stringResource(if (action.done) Res.string.action_mark_todo else Res.string.action_mark_done)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NotitiaTheme.sizes.touchTarget)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) {
                customActions = listOf(CustomAccessibilityAction(toggleLabel) { onToggle(); true })
            }
            .padding(horizontal = spacing.l, vertical = spacing.m),
        horizontalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        Box(Modifier.width(NotitiaTheme.sizes.personAvatar), contentAlignment = Alignment.TopCenter) {
            CheckButton(action.done, onToggle)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            StrikeText(text = action.text, struck = action.done, style = NotitiaTheme.typography.body, maxLines = MAX_LINES)
            when {
                late != null -> DetailsLine(emptyList(), style = NotitiaTheme.typography.caption, alert = late)
                due != null -> DetailsLine(listOf(due), style = NotitiaTheme.typography.caption)
                !action.done -> DetailsLine(
                    listOf(stringResource(Res.string.action_missing_due)),
                    style = NotitiaTheme.typography.caption,
                    color = colors.amber,
                )
            }
        }
    }
}

// A link, not a button, as « Nouveau rappel » at the foot of a list in Reminders: the icon under the circles, the
// words under the actions.
@Composable
private fun NudgeRow(name: String, onClick: () -> Unit) {
    val colors = NotitiaTheme.colors
    val spacing = NotitiaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NotitiaTheme.sizes.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = spacing.l, vertical = spacing.m),
        horizontalArrangement = Arrangement.spacedBy(spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(NotitiaTheme.sizes.personAvatar), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(Res.drawable.chat_bubble),
                contentDescription = null,
                tint = colors.accentContent,
                modifier = Modifier.size(NotitiaTheme.sizes.iconInButton),
            )
        }
        Text(
            text = stringResource(Res.string.nudge_title, name),
            style = NotitiaTheme.typography.body,
            color = colors.accentContent,
        )
    }
}

@Composable
private fun PersonCardsPreview(expandedFirst: Boolean) = NotitiaTheme {
    val now = Clock.System.now()
    val today = today()
    val people = actionsByPerson(trackedActions(sampleMinutes(now)), today)
    var expanded by remember { mutableStateOf(if (expandedFirst) setOf(people.first().key) else emptySet()) }
    Column(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
    ) {
        people.forEach { person ->
            PersonCard(
                person = person,
                today = today,
                expanded = person.key in expanded,
                onExpandedChange = { expanded = if (it) expanded + person.key else expanded - person.key },
                onToggle = {},
                onOpen = {},
                onNudge = person.name?.let { {} },
            )
        }
        Spacer(Modifier.size(NotitiaTheme.spacing.l))
    }
}

@NotitiaPreviews
@Composable
private fun PersonCardsFoldedPreview() = PersonCardsPreview(expandedFirst = false)

@NotitiaPreviews
@Composable
private fun PersonCardsUnfoldedPreview() = PersonCardsPreview(expandedFirst = true)
