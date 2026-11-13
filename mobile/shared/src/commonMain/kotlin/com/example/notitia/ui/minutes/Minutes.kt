package com.example.notitia.ui.minutes

import androidx.compose.runtime.Composable
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.MinutesState
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.PersonColors

// What the minutes screen shows, not the stored minutes: those live in Meeting.
data class Minutes(
    val meeting: MeetingListItem,
    // Named by the user, meeting by meeting: no voiceprint links them across meetings (product rule 4).
    val speakers: List<String>,
    val decisions: List<String>,
    val actions: List<MinutesAction>,
    val summary: String,
)

// The colours of this meeting: an owner who did not speak keeps their own.
val Minutes.people: PersonColors
    @Composable get() = NotitiaTheme.people.inMeeting(speakers)

// Minutes not written yet are empty: the screen shows a notice in their place.
fun Meeting.toMinutes(item: MeetingListItem): Minutes {
    val written = (minutes as? MinutesState.Written)?.minutes
    return Minutes(
        meeting = item,
        speakers = speakers,
        decisions = written?.decisions.orEmpty(),
        actions = written?.actions.orEmpty(),
        summary = written?.summary.orEmpty(),
    )
}
