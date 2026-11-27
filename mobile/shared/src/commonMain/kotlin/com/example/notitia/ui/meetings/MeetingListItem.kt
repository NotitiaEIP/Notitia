package com.example.notitia.ui.meetings

import androidx.compose.runtime.Composable
import com.example.notitia.meeting.Abilities
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MeetingStatus
import com.example.notitia.meeting.MinutesState
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.recording_title
import org.jetbrains.compose.resources.stringResource

// What the list shows, not the stored meeting: that one is Meeting.
data class MeetingListItem(
    val id: String,
    val title: String,
    val startedAt: Instant,
    val duration: Duration,
    // In the order they first spoke, which gives each one its colour.
    val speakers: List<String>,
    val status: MeetingStatus,
) {
    // Whoever recorded, until the speakers are told apart (E2).
    val participants: Int get() = speakers.size.coerceAtLeast(1)
}

// From what the core holds of the meeting and what the engines of this device can do now. late is what the core's
// board counts late in it, remaining the time left to hear it, once the pace of the core is seen.
fun Meeting.status(abilities: Abilities, late: Int = 0, remaining: Duration? = null): MeetingStatus {
    val heard = transcribed
    return when {
        heard != null -> if (abilities.hear) MeetingStatus.Transcribing(share(heard, duration), remaining) else MeetingStatus.Waiting
        minutes == MinutesState.Writing -> if (abilities.write) MeetingStatus.WritingMinutes else MeetingStatus.Waiting
        else -> minutes.status(late)
    }
}

// While it is recorded the core may have heard past the duration it last told (ADR 0012): never past the end.
private fun share(heard: Duration, of: Duration): Float =
    if (of <= Duration.ZERO) 0f else (heard / of).toFloat().coerceIn(0f, 1f)

fun MinutesState.status(late: Int): MeetingStatus = when (this) {
    MinutesState.Writing -> MeetingStatus.WritingMinutes
    is MinutesState.Written -> MeetingStatus.Ready(summary = minutes.summary.ifBlank { null }, late = late)
    MinutesState.Failed, MinutesState.Unavailable -> MeetingStatus.Ready()
}

fun Meeting.listItem(title: String, status: MeetingStatus) = MeetingListItem(
    id = id,
    title = title,
    startedAt = startedAt,
    duration = duration,
    speakers = speakers,
    status = status,
)

// A meeting the user has not named is called after the moment it was recorded.
@Composable
fun Meeting.listItem(status: MeetingStatus): MeetingListItem {
    val start = startedAt.toLocalDateTime(TimeZone.currentSystemDefault())
    val shown = title ?: stringResource(Res.string.recording_title, shortDateLabel(start.date), clockTime(start.time))
    return listItem(shown, status)
}
