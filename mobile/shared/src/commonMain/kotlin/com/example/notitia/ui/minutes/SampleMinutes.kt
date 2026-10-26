package com.example.notitia.ui.minutes

import com.example.notitia.ui.meetings.MeetingListItem
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

val sampleSpeakers = listOf("Lucas", "Clément", "Nathan", "Adam")

// The same made-up minutes for every meeting, until the minutes are written on the phone (E3).
fun sampleMinutes(meeting: MeetingListItem, now: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): Minutes {
    val today = now.toLocalDateTime(timeZone).date
    return Minutes(
        meeting = meeting,
        speakers = sampleSpeakers,
        decisions = listOf(
            "La démo à Epitech a lieu le 15 novembre.",
            "On garde le violet du logo comme accent.",
        ),
        actions = listOf(
            MinutesAction("Préparer la démo de la transcription", owner = 1, due = today.plus(DatePeriod(days = 13)), done = false),
            MinutesAction("Rédiger le registre RGPD", owner = 2, due = today.plus(DatePeriod(days = 17)), done = false),
            MinutesAction("Choisir la police de l’app", owner = 1, due = null, done = true),
        ),
        summary = "L’équipe a validé la nouvelle identité : une interface sobre, un seul accent violet repris du logo.",
    )
}
