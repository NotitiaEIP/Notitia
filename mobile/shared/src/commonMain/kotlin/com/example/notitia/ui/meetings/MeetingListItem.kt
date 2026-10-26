package com.example.notitia.ui.meetings

import kotlin.time.Duration
import kotlin.time.Instant

// What the list shows, not the stored meeting: T-07 defines that one.
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
}
