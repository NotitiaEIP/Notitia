package com.example.notitia.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.example.notitia.ui.meetings.MeetingDay
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.MeetingStatus
import com.example.notitia.ui.meetings.clockTime
import com.example.notitia.ui.meetings.lengthLabel
import com.example.notitia.ui.meetings.meetingDay
import com.example.notitia.ui.meetings.sampleItems
import com.example.notitia.ui.meetings.shortDateLabel
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.actions
import notitia.shared.generated.resources.check
import notitia.shared.generated.resources.minutes_ready
import notitia.shared.generated.resources.minutes_writing
import notitia.shared.generated.resources.participants
import notitia.shared.generated.resources.transcription_progress
import notitia.shared.generated.resources.transcription_remaining
import notitia.shared.generated.resources.weekdays
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

// selected is null while the list is not choosing; then the circle slides in on the left, as in Mail, and a touch
// ticks the card instead of opening it.
@Composable
fun MeetingCard(
    meeting: MeetingListItem,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val spacing = NotitiaTheme.spacing
    val reduced = reducedMotion()
    val details = listOf(
        dayLabel(meetingDay(meeting.startedAt, now, TimeZone.currentSystemDefault())),
        lengthLabel(meeting.duration),
        pluralStringResource(Res.plurals.participants, meeting.participants, meeting.participants),
    )

    NotitiaCard(
        onClick = onClick,
        onLongClick = onLongClick,
        longClickLabel = longClickLabel,
        selected = selected == true,
        modifier = modifier.semantics { if (selected != null) this.selected = selected },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(
                visible = selected != null,
                enter = if (reduced) fadeIn() else fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                exit = if (reduced) fadeOut() else fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
            ) {
                SelectionCheck(selected == true, Modifier.padding(end = spacing.m))
            }
            Column(Modifier.weight(1f)) {
                CardTitle(meeting.title, opens = selected == null)
                DetailsLine(details, Modifier.padding(top = spacing.xs))
                when (val status = meeting.status) {
                    is MeetingStatus.Ready -> ReadyBadges(status, Modifier.padding(top = spacing.m))
                    is MeetingStatus.Transcribing -> TranscriptionProgress(status, Modifier.padding(top = spacing.m))
                    MeetingStatus.WritingMinutes -> NotitiaBadge(
                        text = stringResource(Res.string.minutes_writing),
                        modifier = Modifier.padding(top = spacing.m),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyBadges(status: MeetingStatus.Ready, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
    ) {
        if (status.actions > 0) {
            NotitiaBadge(text = pluralStringResource(Res.plurals.actions, status.actions, status.actions))
        }
        NotitiaBadge(
            text = stringResource(Res.string.minutes_ready),
            tone = BadgeTone.Success,
            icon = painterResource(Res.drawable.check),
        )
    }
}

@Composable
private fun TranscriptionProgress(status: MeetingStatus.Transcribing, modifier: Modifier = Modifier) {
    val colors = NotitiaTheme.colors
    val shape = NotitiaTheme.shapes.full
    Column(modifier) {
        // Decorative: the line below already says the percentage.
        Box(
            Modifier
                .fillMaxWidth()
                .height(NotitiaTheme.sizes.progressBar)
                .clip(shape)
                .background(colors.divider)
                .clearAndSetSemantics {},
        ) {
            Box(
                Modifier
                    .fillMaxWidth(status.progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(shape)
                    .background(colors.accent),
            )
        }
        DetailsLine(
            parts = listOf(
                stringResource(Res.string.transcription_progress, (status.progress * 100).roundToInt()),
                stringResource(Res.string.transcription_remaining, lengthLabel(status.remaining)),
            ),
            modifier = Modifier.padding(top = NotitiaTheme.spacing.s),
        )
    }
}

// Today and yesterday show the time: the group above already says the day.
@Composable
private fun dayLabel(day: MeetingDay): String = when (day) {
    is MeetingDay.Today -> clockTime(day.time)
    is MeetingDay.Yesterday -> clockTime(day.time)
    is MeetingDay.ThisWeek -> stringArrayResource(Res.array.weekdays)[day.dayOfWeek.ordinal]
    is MeetingDay.Earlier -> shortDateLabel(day.date)
}

@NotitiaPreviews
@Composable
private fun MeetingCardPreview() = NotitiaTheme {
    val now = Clock.System.now()
    Column(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
    ) {
        sampleItems(now).take(2).forEach { MeetingCard(meeting = it, now = now, onClick = {}) }
    }
}
