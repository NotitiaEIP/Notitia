package com.example.notitia.ui.minutes

import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.notitia.ui.components.BackButton
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
import com.example.notitia.ui.recording.MeetingTranscription
import com.example.notitia.ui.recording.TranscriptLine
import com.example.notitia.ui.recording.timerText
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.shareIcon
import kotlin.time.Clock
import kotlin.time.Instant
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.action_due
import notitia.shared.generated.resources.action_no_owner
import notitia.shared.generated.resources.check_circle_filled
import notitia.shared.generated.resources.meetings_title
import notitia.shared.generated.resources.minutes_actions
import notitia.shared.generated.resources.minutes_back_description
import notitia.shared.generated.resources.minutes_decisions
import notitia.shared.generated.resources.minutes_failed
import notitia.shared.generated.resources.minutes_more
import notitia.shared.generated.resources.minutes_no_actions
import notitia.shared.generated.resources.minutes_no_decisions
import notitia.shared.generated.resources.minutes_no_summary
import notitia.shared.generated.resources.minutes_share
import notitia.shared.generated.resources.minutes_summary
import notitia.shared.generated.resources.minutes_tab_minutes
import notitia.shared.generated.resources.minutes_tab_transcript
import notitia.shared.generated.resources.minutes_unavailable
import notitia.shared.generated.resources.minutes_writing
import notitia.shared.generated.resources.more_horiz
import notitia.shared.generated.resources.participants
import notitia.shared.generated.resources.radio_button_unchecked
import notitia.shared.generated.resources.transcript_empty
import notitia.shared.generated.resources.transcript_failed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// Holds what the user ticks on the minutes, until they are stored (US-16).
// recorded is null for a made-up meeting, which keeps its made-up minutes.
@Composable
fun MinutesRoute(
    meeting: MeetingListItem,
    recorded: MeetingTranscription?,
    backLabel: String,
    backDescription: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = remember { Clock.System.now() }
    val state = recorded?.minutes
    var minutes by remember(meeting.id, state) {
        mutableStateOf(
            when (state) {
                null -> sampleMinutes(meeting, now)
                is MinutesState.Written -> Minutes(
                    meeting = meeting,
                    // The gateway does not tell the voices apart (E2).
                    speakers = emptyList(),
                    decisions = state.minutes.decisions,
                    actions = state.minutes.actions,
                    summary = state.minutes.summary,
                )
                else -> Minutes(meeting, emptyList(), emptyList(), emptyList(), summary = "")
            },
        )
    }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        onBackCompleted = onBack,
    )
    MinutesScreen(
        minutes = minutes,
        notice = when (state) {
            MinutesState.Writing -> Res.string.minutes_writing
            MinutesState.Failed -> Res.string.minutes_failed
            MinutesState.Unavailable -> Res.string.minutes_unavailable
            null, is MinutesState.Written -> null
        },
        now = now,
        backLabel = backLabel,
        backDescription = backDescription,
        onBack = onBack,
        onToggleAction = { index ->
            minutes = minutes.copy(
                actions = minutes.actions.mapIndexed { i, action ->
                    if (i == index) action.copy(done = !action.done) else action
                },
            )
        },
        transcript = recorded?.lines,
        modifier = modifier,
    )
}

// notice replaces the minutes when they are not written.
@Composable
fun MinutesScreen(
    minutes: Minutes,
    now: Instant,
    backLabel: String,
    backDescription: String,
    onBack: () -> Unit,
    onToggleAction: (Int) -> Unit,
    notice: StringResource? = null,
    transcript: List<TranscriptLine>? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = NotitiaTheme.spacing
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val meeting = minutes.meeting

    Column(modifier.fillMaxSize()) {
        TopBar(backLabel, backDescription, onBack)
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
            if (minutes.speakers.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = spacing.l),
                    horizontalArrangement = Arrangement.spacedBy(spacing.s),
                    verticalArrangement = Arrangement.spacedBy(spacing.s),
                ) {
                    minutes.speakers.forEachIndexed { index, name -> SpeakerChip(name, index) }
                }
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
            // The made-up meetings have no transcript: their tab stays empty.
            when {
                tab != 0 -> transcript?.let { TranscriptContent(it) }
                notice != null -> Notice(stringResource(notice), Modifier.padding(top = spacing.xxl))
                else -> MinutesContent(minutes, onToggleAction)
            }
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
private fun TopBar(backLabel: String, backDescription: String, onBack: () -> Unit) {
    val colors = NotitiaTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NotitiaTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(backLabel, backDescription, onBack)
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
    if (minutes.decisions.isEmpty()) Notice(stringResource(Res.string.minutes_no_decisions))
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s)) {
        minutes.decisions.forEach { Bullet(it) }
    }

    SectionHeader(
        title = stringResource(Res.string.minutes_actions),
        count = minutes.actions.size,
        modifier = Modifier.padding(top = spacing.xxl, bottom = spacing.xs),
    )
    if (minutes.actions.isEmpty()) Notice(stringResource(Res.string.minutes_no_actions))
    minutes.actions.forEachIndexed { index, action ->
        ActionRow(
            action = action,
            speaker = minutes.speakers.indexOf(action.owner),
            onToggle = { onToggleAction(index) },
        )
    }

    SectionHeader(
        title = stringResource(Res.string.minutes_summary),
        modifier = Modifier.padding(top = spacing.xxl, bottom = spacing.s),
    )
    if (minutes.summary.isEmpty()) {
        Notice(stringResource(Res.string.minutes_no_summary))
    } else {
        Text(text = minutes.summary, style = NotitiaTheme.typography.body)
    }
}

@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = NotitiaTheme.typography.body,
        color = NotitiaTheme.colors.textSecondary,
        modifier = modifier,
    )
}

@Composable
private fun TranscriptContent(lines: List<TranscriptLine>) {
    val colors = NotitiaTheme.colors
    val spacing = NotitiaTheme.spacing
    val typography = NotitiaTheme.typography
    Column(
        modifier = Modifier.padding(top = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        if (lines.isEmpty()) Notice(stringResource(Res.string.transcript_empty))
        lines.forEach { line ->
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                Text(text = timerText(line.at), style = typography.caption, color = colors.textSecondary)
                Text(
                    text = line.text ?: stringResource(Res.string.transcript_failed),
                    style = typography.body,
                    color = if (line.text == null) colors.textSecondary else colors.text,
                )
            }
        }
    }
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
// speaker is -1 when the owner is not one of the speakers, who then has no colour.
@Composable
private fun ActionRow(action: MinutesAction, speaker: Int, onToggle: () -> Unit) {
    val colors = NotitiaTheme.colors
    val spacing = NotitiaTheme.spacing
    val details = buildList {
        add(action.owner ?: stringResource(Res.string.action_no_owner))
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
                if (speaker >= 0) SpeakerDot(speaker)
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
        backLabel = stringResource(Res.string.meetings_title),
        backDescription = stringResource(Res.string.minutes_back_description),
        onBack = {},
        onToggleAction = {},
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}
