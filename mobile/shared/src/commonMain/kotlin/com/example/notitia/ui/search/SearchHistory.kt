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

// Kept in memory only, like the searches themselves (product rule 1). A title renames what the list shows; the
// search itself stays its text.
data class HistoryEntry(
    val text: String,
    val kind: QueryKind,
    val at: Instant,
    val title: String? = null,
    val pinned: Boolean = false,
) {
    val label: String get() = title ?: text
}

sealed interface HistoryGroup {
    data object Pinned : HistoryGroup
    data class Day(val group: MeetingGroup) : HistoryGroup
}

private fun HistoryEntry.isSame(other: HistoryEntry) = sameQuestion(text, other.text)

// A search launched again moves to the top, with its title and its pin: "budget" and "Budget" are one search,
// with or without the "?".
fun List<HistoryEntry>.add(entry: HistoryEntry): List<HistoryEntry> {
    val old = firstOrNull { it.isSame(entry) }
    val new = if (old == null) entry else entry.copy(title = old.title, pinned = old.pinned)
    return listOf(new) + filterNot { it.isSame(entry) }
}

fun List<HistoryEntry>.pin(entry: HistoryEntry, pinned: Boolean): List<HistoryEntry> =
    map { if (it.isSame(entry)) it.copy(pinned = pinned) else it }

// An empty title, or the text itself, gives the search its text back.
fun List<HistoryEntry>.rename(entry: HistoryEntry, title: String): List<HistoryEntry> {
    val name = title.trim().takeIf { it.isNotEmpty() && it != entry.text }
    return map { if (it.isSame(entry)) it.copy(title = name) else it }
}

fun List<HistoryEntry>.remove(entry: HistoryEntry): List<HistoryEntry> = filterNot { it.isSame(entry) }

// The pinned searches first, then the others by day, as in the meetings list. Each group keeps the order of the
// history: the most recent first.
fun historyGroups(
    entries: List<HistoryEntry>,
    now: Instant,
    timeZone: TimeZone,
): List<Pair<HistoryGroup, List<HistoryEntry>>> {
    val (pinned, others) = entries.partition { it.pinned }
    val days = others.groupBy { HistoryGroup.Day(meetingDay(it.at, now, timeZone).group()) }.toList()
    return if (pinned.isEmpty()) days else listOf(HistoryGroup.Pinned to pinned) + days
}

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
