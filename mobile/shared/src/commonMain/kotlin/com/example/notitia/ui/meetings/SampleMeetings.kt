package com.example.notitia.ui.meetings

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

// Made-up meetings, one per kind of group, until US-16 reads the stored ones.
fun sampleMeetings(now: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): List<MeetingListItem> {
    val today = now.toLocalDateTime(timeZone).date
    fun todayAt(hour: Int, minute: Int) = today.atTime(hour, minute).toInstant(timeZone)
    return listOf(
        MeetingListItem(
            "sample-1", "Point d’avancement Notitia", todayAt(14, 2), 42.minutes, 4,
            MeetingStatus.Ready(actions = 3),
        ),
        MeetingListItem(
            "sample-2", "Entretien utilisateur, CHU", todayAt(10, 30), 28.minutes, 2,
            MeetingStatus.Transcribing(progress = 0.64f, remaining = 2.minutes),
        ),
        MeetingListItem(
            "sample-3", "Atelier sur l’écran d’accueil", todayAt(16, 0) - 1.days, 55.minutes, 3,
            MeetingStatus.Ready(actions = 1),
        ),
        MeetingListItem(
            "sample-4", "Revue de sprint 1", todayAt(9, 30) - 3.days, 1.hours + 5.minutes, 6,
            MeetingStatus.Ready(actions = 5),
        ),
        MeetingListItem(
            "sample-5", "Lancement du projet", todayAt(11, 0) - 40.days, 1.hours + 30.minutes, 6,
            MeetingStatus.Ready(actions = 0),
        ),
    )
}
