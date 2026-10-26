package com.example.notitia.ui.minutes

import com.example.notitia.ui.meetings.MeetingListItem
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
    val owner: Int,
    val due: LocalDate?,
    val done: Boolean,
)
