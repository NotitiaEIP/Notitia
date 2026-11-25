package com.example.notitia.meeting

import kotlin.time.Duration

// What a card says of a meeting, as data: the words are the screen's.
sealed interface MeetingStatus {
    // summary opens the card, as a mail's first lines in Gmail; late is what its line says in red.
    data class Ready(val summary: String? = null, val late: Int = 0) : MeetingStatus

    // remaining is null until the pace of the core is seen.
    data class Transcribing(val progress: Float, val remaining: Duration? = null) : MeetingStatus
    data object WritingMinutes : MeetingStatus

    // It owes what no engine of this device can do now: it goes on once a model is there.
    data object Waiting : MeetingStatus
}
