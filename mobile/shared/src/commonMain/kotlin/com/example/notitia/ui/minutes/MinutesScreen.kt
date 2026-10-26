package com.example.notitia.ui.minutes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.notitia.ui.components.DetailsLine
import com.example.notitia.ui.components.NotitiaButton
import com.example.notitia.ui.components.NotitiaSegmentedControl
import com.example.notitia.ui.components.SectionHeader
import com.example.notitia.ui.components.SpeakerChip
import com.example.notitia.ui.components.SpeakerDot
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.lengthLabel
import com.example.notitia.ui.meetings.meetingWhenLabel
import com.example.notitia.ui.meetings.sampleMeetings
import com.example.notitia.ui.meetings.shortDateLabel
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.shareIcon
import kotlin.time.Clock
import kotlin.time.Instant
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.action_due
import notitia.shared.generated.resources.check_circle_filled
import notitia.shared.generated.resources.chevron_left
import notitia.shared.generated.resources.meetings_title
import notitia.shared.generated.resources.minutes_actions
import notitia.shared.generated.resources.minutes_back_description
import notitia.shared.generated.resources.minutes_decisions
import notitia.shared.generated.resources.minutes_more
import notitia.shared.generated.resources.minutes_share
import notitia.shared.generated.resources.minutes_summary
import notitia.shared.generated.resources.minutes_tab_minutes
import notitia.shared.generated.resources.minutes_tab_transcript
import notitia.shared.generated.resources.more_horiz
import notitia.shared.generated.resources.participants
import notitia.shared.generated.resources.radio_button_unchecked
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// Holds what the user changes on the made-up minutes, until they are stored (US-16).
@Composable
fun MinutesRoute(meeting: MeetingListItem, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val now = remember { Clock.System.now() }
    var minutes by remember(meeting.id) { mutableStateOf(sampleMinutes(meeting, now)) }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        onBackCompleted = onBack,
    )
    MinutesScreen(
        minutes = minutes,
        now = now,
        onBack = onBack,
        onToggleAction = { index ->
            minutes = minutes.copy(
                actions = minutes.actions.mapIndexed { i, action ->
                    if (i == index) action.copy(done = !action.done) else action
                },
            )
        },
        modifier = modifier,
    )
}

@Composable
fun MinutesScreen(
    minutes: Minutes,
    now: Instant,
    onBack: () -> Unit,
    onToggleAction: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NotitiaTheme.spacing
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val meeting = minutes.meeting

    Column(modifier.fillMaxSize()) {
        TopBar(onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.l)
                .padding(bottom = spacing.xl),
        ) {
            Text(
                text = meeting.title,
                style = NotitiaTheme.typography.title1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            DetailsLine(
                parts = listOf(
                    meetingWhenLabel(meeting.startedAt, now),
                    lengthLabel(meeting.duration),
                    pluralStringResource(Res.plurals.participants, meeting.participants, meeting.participants),
                ),
                modifier = Modifier.padding(top = spacing.xs),
            )
            FlowRow(
                modifier = Modifier.padding(top = spacing.l),
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
                verticalArrangement = Arrangement.spacedBy(spacing.s),
            ) {
                minutes.speakers.forEachIndexed { index, name -> SpeakerChip(name, index) }
            }
            NotitiaSegmentedControl(
                options = listOf(
                    stringResource(Res.string.minutes_tab_minutes),
                    stringResource(Res.string.minutes_tab_transcript),
                ),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(top = spacing.xl),
            )
            // The transcript tab stays empty until the transcription exists (E2).
            if (tab == 0) MinutesContent(minutes, onToggleAction)
        }
        HorizontalDivider(color = NotitiaTheme.colors.divider)
        NotitiaButton(
            text = stringResource(Res.string.minutes_share),
            onClick = {},
            icon = painterResource(shareIcon),
            modifier = Modifier.fillMaxWidth().padding(spacing.l),
        )
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    val colors = NotitiaTheme.colors
    val backDescription = stringResource(Res.string.minutes_back_description)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NotitiaTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = NotitiaTheme.sizes.touchTarget)
                .clickable(role = Role.Button, onClick = onBack)
                .semantics(mergeDescendants = true) { contentDescription = backDescription }
                .padding(end = NotitiaTheme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.chevron_left),
                contentDescription = null,
                tint = colors.accentContent,
            )
            Text(
                text = stringResource(Res.string.meetings_title),
                style = NotitiaTheme.typography.label,
                color = colors.accentContent,
            )
        }
        Box(Modifier.weight(1f))
        // The menu (rename, delete) comes with US-16.
        IconButton(onClick = {}, modifier = Modifier.size(NotitiaTheme.sizes.touchTarget)) {
            Icon(
                painter = painterResource(Res.drawable.more_horiz),
                contentDescription = stringResource(Res.string.minutes_more),
                tint = colors.text,
            )
        }
    }
}

@Composable
private fun MinutesContent(minutes: Minutes, onToggleAction: (Int) -> Unit) {
    val spacing = NotitiaTheme.spacing
    SectionHeader(
        title = stringResource(Res.string.minutes_decisions),
        count = minutes.decisions.size,
        modifier = Modifier.padding(top = spacing.xxl, bottom = spacing.s),
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s)) {
        minutes.decisions.forEach { Bullet(it) }
    }

    SectionHeader(
        title = stringResource(Res.string.minutes_actions),
        count = minutes.actions.size,
        modifier = Modifier.padding(top = spacing.xxl, bottom = spacing.xs),
    )
    minutes.actions.forEachIndexed { index, action ->
        ActionRow(
            action = action,
            owner = minutes.speakers.getOrElse(action.owner) { "" },
            onToggle = { onToggleAction(index) },
        )
    }

    SectionHeader(
        title = stringResource(Res.string.minutes_summary),
        modifier = Modifier.padding(top = spacing.xxl, bottom = spacing.s),
    )
    Text(text = minutes.summary, style = NotitiaTheme.typography.body)
}

@Composable
private fun Bullet(text: String) {
    val style = NotitiaTheme.typography.body
    // As tall as the first line, so the dot stays centred on it whatever the text size.
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Row(horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.m)) {
        Box(Modifier.height(lineHeight), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(NotitiaTheme.sizes.bullet)
                    .background(NotitiaTheme.colors.textSecondary, NotitiaTheme.shapes.full),
            )
        }
        Text(text = text, style = style)
    }
}

// The whole row checks the action, so the target is large and screen readers read it in one go:
// "Préparer la démo, Clément, avant le 8 nov., case à cocher, non cochée".
@Composable
private fun ActionRow(action: MinutesAction, owner: String, onToggle: () -> Unit) {
    val colors = NotitiaTheme.colors
    val spacing = NotitiaTheme.spacing
    val details = buildList {
        add(owner)
        action.due?.let { add(stringResource(Res.string.action_due, shortDateLabel(it))) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NotitiaTheme.sizes.touchTarget)
            .toggleable(value = action.done, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = spacing.s),
        horizontalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        Icon(
            painter = painterResource(
                if (action.done) Res.drawable.check_circle_filled else Res.drawable.radio_button_unchecked,
            ),
            contentDescription = null,
            tint = if (action.done) colors.green else colors.textSecondary,
            modifier = Modifier.size(NotitiaTheme.sizes.icon),
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                text = action.text,
                style = NotitiaTheme.typography.body.copy(
                    textDecoration = if (action.done) TextDecoration.LineThrough else null,
                ),
                color = if (action.done) colors.textTertiary else colors.text,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpeakerDot(action.owner)
                DetailsLine(details, style = NotitiaTheme.typography.caption)
            }
        }
    }
}

@NotitiaPreviews
@Composable
private fun MinutesScreenPreview() = NotitiaTheme {
    val now = Clock.System.now()
    MinutesScreen(
        minutes = sampleMinutes(sampleMeetings(now).first(), now),
        now = now,
        onBack = {},
        onToggleAction = {},
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}
