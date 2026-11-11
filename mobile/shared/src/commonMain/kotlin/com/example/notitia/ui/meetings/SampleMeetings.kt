package com.example.notitia.ui.meetings

import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.TranscriptLine
import com.example.notitia.ui.minutes.Minutes
import com.example.notitia.ui.minutes.sampleSpeakers
import com.example.notitia.ui.minutes.sampleWrittenMinutes
import com.example.notitia.ui.minutes.toMinutes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

// Made-up meetings, one per kind of group, shown beside the user's own and never written to the phone.
fun sampleMeetings(now: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): List<Meeting> {
    val today = now.toLocalDateTime(timeZone).date
    fun todayAt(hour: Int, minute: Int) = today.atTime(hour, minute).toInstant(timeZone)
    fun sample(id: String, title: String, startedAt: Instant, duration: Duration, writing: Boolean = false): Meeting {
        val written = sampleWrittenMinutes(id, today)
        return Meeting(
            id = id,
            startedAt = startedAt,
            duration = duration,
            modifiedAt = startedAt,
            device = "sample",
            title = title,
            speakers = sampleSpeakers,
            // The sentences the actions quote, so the transcript tab has something to show.
            transcript = written.actions.mapNotNull { it.source }.sortedBy { it.at }
                .map { TranscriptLine(it.at, it.quote) },
            minutes = if (writing) MinutesState.Writing else MinutesState.Written(written),
        )
    }
    return listOf(
        sample("sample-1", "Point d’avancement Notitia", todayAt(14, 2), 42.minutes),
        sample("sample-2", "Entretien utilisateur, CHU", todayAt(10, 30), 28.minutes, writing = true),
        sample("sample-3", "Atelier sur l’écran d’accueil", todayAt(16, 0) - 1.days, 55.minutes),
        sample("sample-4", "Revue de sprint 1", todayAt(9, 30) - 3.days, 1.hours + 5.minutes),
        sample("sample-5", "Lancement du projet", todayAt(11, 0) - 40.days, 1.hours + 30.minutes),
    )
}

// For previews and tests, which show the samples without a repository.
fun sampleItems(now: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): List<MeetingListItem> =
    sampleMeetings(now, timeZone).map { it.listItem(title = checkNotNull(it.title)) }

fun sampleMinutes(now: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): List<Minutes> =
    sampleMeetings(now, timeZone).map { it.toMinutes(it.listItem(title = checkNotNull(it.title))) }
