package com.example.notitia.ui.minutes

import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.MinutesState
import com.example.notitia.ui.meetings.MeetingListItem
import kotlin.time.Instant

// What the minutes screen shows, not the stored minutes: those live in Meeting.
data class Minutes(
    val meeting: MeetingListItem,
    // Named by the user, meeting by meeting: no voiceprint links them across meetings (product rule 4).
    val speakers: List<String>,
    val decisions: List<String>,
    val actions: List<MinutesAction>,
    val summary: String,
)

// The owner has a colour only when they are one of the meeting's speakers.
fun Minutes.speakerOf(action: MinutesAction): Int? = speakers.indexOf(action.owner).takeIf { it >= 0 }

// state is null for a made-up meeting, which keeps its made-up minutes. The minutes stay empty until written.
fun meetingMinutes(meeting: MeetingListItem, state: MinutesState?, now: Instant): Minutes = when (state) {
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
}
