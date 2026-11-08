package com.example.notitia.ui.minutes

import com.example.notitia.ui.meetings.MeetingListItem
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

// What the minutes screen shows, not the stored minutes: T-07 defines those.
data class Minutes(
    val meeting: MeetingListItem,
    // Named by the user, meeting by meeting: no voiceprint links them across meetings (product rule 4).
    val speakers: List<String>,
    val decisions: List<String>,
    val actions: List<MinutesAction>,
    val summary: String,
)

data class MinutesAction(
    val text: String,
    // A first name, as said in the meeting: null when nobody was named.
    val owner: String?,
    val due: LocalDate?,
    val done: Boolean,
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
