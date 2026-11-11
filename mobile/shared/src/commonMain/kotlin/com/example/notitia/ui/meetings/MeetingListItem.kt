package com.example.notitia.ui.meetings

import androidx.compose.runtime.Composable
import com.example.notitia.meeting.Meeting
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
    val participants: Int,
    val status: MeetingStatus,
)

sealed interface MeetingStatus {
    data class Ready(val actions: Int) : MeetingStatus
    data class Transcribing(val progress: Float, val remaining: Duration) : MeetingStatus
    data object WritingMinutes : MeetingStatus
}

fun MinutesState.status(): MeetingStatus = when (this) {
    MinutesState.Writing -> MeetingStatus.WritingMinutes
    is MinutesState.Written -> MeetingStatus.Ready(actions = minutes.actions.size)
    MinutesState.Failed, MinutesState.Unavailable -> MeetingStatus.Ready(actions = 0)
}

// progress is given while the meeting is still transcribed: the kept meeting does not hold it.
@Composable
fun Meeting.listItem(progress: MeetingStatus? = null): MeetingListItem {
    val start = startedAt.toLocalDateTime(TimeZone.currentSystemDefault())
    return MeetingListItem(
        id = id,
        title = title ?: stringResource(Res.string.recording_title, shortDateLabel(start.date), clockTime(start.time)),
        startedAt = startedAt,
        duration = duration,
        // Whoever recorded, until the speakers are told apart (E2).
        participants = speakers.size.coerceAtLeast(1),
        status = progress ?: minutes.status(),
    )
}
