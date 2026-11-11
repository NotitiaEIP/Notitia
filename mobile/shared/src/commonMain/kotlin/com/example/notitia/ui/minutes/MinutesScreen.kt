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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.TranscriptLine
import com.example.notitia.ui.actions.ActionsState
import com.example.notitia.ui.components.ActionRow
import com.example.notitia.ui.components.BackButton
import com.example.notitia.ui.components.DetailsLine
import com.example.notitia.ui.components.NotitiaButton
import com.example.notitia.ui.components.NotitiaSegmentedControl
import com.example.notitia.ui.components.SectionHeader
import com.example.notitia.ui.components.SpeakerChip
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.lengthLabel
import com.example.notitia.ui.meetings.meetingWhenLabel
import com.example.notitia.ui.meetings.sampleMeetings
import com.example.notitia.ui.recording.timerText
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.shareIcon
import kotlin.time.Clock
import kotlin.time.Instant
import notitia.shared.generated.resources.Res
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
import notitia.shared.generated.resources.transcript_empty
import notitia.shared.generated.resources.transcript_failed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// recorded is null for a made-up meeting.
@Composable
fun MinutesRoute(
    meeting: MeetingListItem,
    recorded: Meeting?,
    actions: ActionsState,
    backLabel: String,
    backDescription: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = remember { Clock.System.now() }
    val state = recorded?.minutes
    val written = remember(meeting, state) { meetingMinutes(meeting, state, now) }
    val minutes = actions.applied(written)
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
        onToggleAction = { index -> actions.setDone(ActionId(meeting.id, index), !minutes.actions[index].done) },
        transcript = recorded?.transcript,
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
        ActionRow(action = action, onToggle = { onToggleAction(index) }, speaker = minutes.speakerOf(action))
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
