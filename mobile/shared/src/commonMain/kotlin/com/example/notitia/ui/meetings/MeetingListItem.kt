package com.example.notitia.ui.meetings

import androidx.compose.runtime.Composable
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.ui.actions.DueGroup
import com.example.notitia.ui.actions.dueGroup
import com.example.notitia.ui.actions.today
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
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

sealed interface MeetingStatus {
    // summary opens the card, as a mail's first lines in Gmail; late is what its line says in red.
    data class Ready(val summary: String? = null, val late: Int = 0) : MeetingStatus
    data class Transcribing(val progress: Float, val remaining: Duration) : MeetingStatus
    data object WritingMinutes : MeetingStatus
}

fun MinutesState.status(today: LocalDate): MeetingStatus = when (this) {
    MinutesState.Writing -> MeetingStatus.WritingMinutes
    is MinutesState.Written -> with(minutes.actions) {
        MeetingStatus.Ready(
            summary = minutes.summary.ifBlank { null },
            late = count { !it.done && it.due?.let { due -> dueGroup(due, today) } == DueGroup.Late },
        )
    }
    MinutesState.Failed, MinutesState.Unavailable -> MeetingStatus.Ready()
}

// progress is given while the meeting is still transcribed: the kept meeting does not hold it.
fun Meeting.listItem(title: String, today: LocalDate, progress: MeetingStatus? = null) = MeetingListItem(
    id = id,
    title = title,
    startedAt = startedAt,
    duration = duration,
    speakers = speakers,
    status = progress ?: minutes.status(today),
)

// A meeting the user has not named is called after the moment it was recorded.
@Composable
fun Meeting.listItem(progress: MeetingStatus? = null): MeetingListItem {
    val start = startedAt.toLocalDateTime(TimeZone.currentSystemDefault())
    val shown = title ?: stringResource(Res.string.recording_title, shortDateLabel(start.date), clockTime(start.time))
    return listItem(shown, today(), progress)
}
