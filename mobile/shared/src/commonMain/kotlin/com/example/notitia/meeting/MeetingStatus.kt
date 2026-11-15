package com.example.notitia.meeting

import kotlin.time.Duration

// What a card says of a meeting, as data: the words are the screen's.
sealed interface MeetingStatus {
    // summary opens the card, as a mail's first lines in Gmail; late is what its line says in red.
    data class Ready(val summary: String? = null, val late: Int = 0) : MeetingStatus
    data class Transcribing(val progress: Float, val remaining: Duration) : MeetingStatus
    data object WritingMinutes : MeetingStatus
}
