package com.example.notitia.ui.search

import com.example.notitia.ui.meetings.MeetingGroup
import com.example.notitia.ui.meetings.group
import com.example.notitia.ui.meetings.meetingDay
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

// Kept in memory only, like the searches themselves (product rule 1).
data class HistoryEntry(val text: String, val kind: QueryKind, val at: Instant)

// A search launched again moves to the top: "budget" and "Budget" are one search, with or without the "?".
fun List<HistoryEntry>.add(entry: HistoryEntry): List<HistoryEntry> =
    listOf(entry) + filterNot { sameQuestion(it.text, entry.text) }

// The same groups as the meetings list, in the order of the history: the most recent first.
fun historyByDay(
    entries: List<HistoryEntry>,
    now: Instant,
    timeZone: TimeZone,
): List<Pair<MeetingGroup, List<HistoryEntry>>> =
    entries.groupBy { meetingDay(it.at, now, timeZone).group() }.toList()

// Made-up searches until the history is stored (E6). Dated on the calendar, so each one lands in its
// group at any time of day.
fun sampleHistory(now: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): List<HistoryEntry> {
    val today = now.toLocalDateTime(timeZone).date
    fun at(daysAgo: Int, hour: Int, minute: Int) = today.atTime(hour, minute).toInstant(timeZone) - daysAgo.days
    fun entry(text: String, daysAgo: Int, hour: Int, minute: Int) =
        HistoryEntry(text, queryKind(text), at(daysAgo, hour, minute))
    return listOf(
        entry("Qu’a-t-on décidé pour la démo ?", 0, 11, 40),
        entry("budget", 0, 9, 15),
        entry("Décisions de la semaine", 1, 17, 5),
        entry("Qui prépare la répétition ?", 1, 10, 20),
        entry("registre RGPD", 2, 16, 30),
        entry("police de l’app", 3, 14, 10),
        entry("Quand a lieu la soutenance ?", 4, 9, 0),
    )
}
