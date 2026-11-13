package com.example.notitia.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.example.notitia.ui.components.CardChevron
import com.example.notitia.ui.components.CardTitle
import com.example.notitia.ui.components.DetailsLine
import com.example.notitia.ui.components.NotitiaCard
import com.example.notitia.ui.components.SpeakerDot
import com.example.notitia.ui.components.spokenList
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.lengthLabel
import com.example.notitia.ui.meetings.meetingWhenLabel
import com.example.notitia.ui.meetings.sampleItems
import com.example.notitia.ui.recording.timerText
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import kotlin.time.Clock
import kotlin.time.Instant
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.search_quote
import notitia.shared.generated.resources.search_source
import org.jetbrains.compose.resources.stringResource

// A meeting that holds the words searched: the whole card opens its minutes.
@Composable
fun FoundMeetingCard(
    meeting: MeetingListItem,
    passages: List<Passage>,
    query: String,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NotitiaCard(onClick = onClick, modifier = modifier) {
        CardTitle(meeting.title)
        DetailsLine(
            parts = listOf(meetingWhenLabel(meeting.startedAt, now), lengthLabel(meeting.duration)),
            modifier = Modifier.padding(top = NotitiaTheme.spacing.xs),
        )
        Column(
            modifier = Modifier.padding(top = NotitiaTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.m),
        ) {
            passages.forEach { PassageLine(it, highlighted(it.text, highlights(it.text, query))) }
        }
    }
}

@Composable
fun SourceCard(
    number: Int,
    passage: Passage,
    meeting: MeetingListItem,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NotitiaTheme.colors
    val sourceLabel = stringResource(Res.string.search_source, number)
    NotitiaCard(onClick = onClick, modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CitationBadge(number, Modifier.clearAndSetSemantics { contentDescription = sourceLabel })
            Column(Modifier.weight(1f)) {
                Text(
                    text = meeting.title,
                    style = NotitiaTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meetingWhenLabel(meeting.startedAt, now),
                    style = NotitiaTheme.typography.caption,
                    color = colors.textSecondary,
                )
            }
            CardChevron()
        }
        PassageLine(
            passage = passage,
            text = AnnotatedString(stringResource(Res.string.search_quote, passage.text)),
            modifier = Modifier.padding(top = NotitiaTheme.spacing.s),
        )
    }
}

// Grows with the text, so the number still fits at 200 %.
@Composable
fun citationSize(): Dp = NotitiaTheme.sizes.citation * LocalDensity.current.fontScale

// The number of a source, in the answer and on its card.
@Composable
fun CitationBadge(number: Int, modifier: Modifier = Modifier) {
    val size = citationSize()
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = size, minHeight = size)
            .background(NotitiaTheme.colors.accentSoft, NotitiaTheme.shapes.badge)
            .padding(horizontal = NotitiaTheme.spacing.xxs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = NotitiaTheme.typography.badge,
            color = NotitiaTheme.colors.accentContent,
        )
    }
}

// "12:04 • Adam Le budget…": wrapped lines start under the text, not under the time. Screen readers get it
// in one go, with pauses: "12:04, Adam, Le budget…".
@Composable
private fun PassageLine(passage: Passage, text: AnnotatedString, modifier: Modifier = Modifier) {
    val typography = NotitiaTheme.typography
    val time = timerText(passage.at)
    // As tall as the first line, so the dot stays centred on it whatever the text size.
    val firstLine = with(LocalDensity.current) { typography.callout.lineHeight.toDp() }
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = spokenList(listOf(time, passage.name, text.text)) },
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
    ) {
        Text(
            text = time,
            style = typography.timestamp,
            color = NotitiaTheme.colors.textTertiary,
            modifier = Modifier.alignByBaseline(),
        )
        Box(Modifier.height(firstLine), contentAlignment = Alignment.Center) {
            SpeakerDot(NotitiaTheme.people.of(passage.name))
        }
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = typography.bodyStrong.fontWeight)) { append(passage.name) }
                append(" ")
                append(text)
            },
            style = typography.callout,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

@Composable
private fun highlighted(text: String, ranges: List<IntRange>): AnnotatedString {
    val colors = NotitiaTheme.colors
    val found = SpanStyle(
        fontWeight = NotitiaTheme.typography.bodyStrong.fontWeight,
        color = colors.accentContent,
        background = colors.accentSoft,
    )
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(found, it.first, it.last + 1) }
    }
}

@NotitiaPreviews
@Composable
private fun FoundMeetingCardPreview() = NotitiaTheme {
    val now = Clock.System.now()
    val meetings = sampleItems(now)
    Column(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
    ) {
        groupByMeeting(findPassages("budget", samplePassages), meetings).take(2).forEach { (meeting, passages) ->
            FoundMeetingCard(meeting, passages, query = "budget", now = now, onClick = {})
        }
    }
}

@NotitiaPreviews
@Composable
private fun SourceCardPreview() = NotitiaTheme {
    val now = Clock.System.now()
    val passage = samplePassages[1]
    Box(Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l)) {
        SourceCard(
            number = 1,
            passage = passage,
            meeting = sampleItems(now).first { it.id == passage.meetingId },
            now = now,
            onClick = {},
        )
    }
}

@NotitiaPreviews
@Composable
private fun CitationBadgePreview() = NotitiaTheme {
    Row(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
    ) {
        CitationBadge(1)
        CitationBadge(12)
    }
}
