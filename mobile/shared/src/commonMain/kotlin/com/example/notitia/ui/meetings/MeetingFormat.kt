package com.example.notitia.ui.meetings

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

sealed interface MeetingDay {
    data class Today(val time: LocalTime) : MeetingDay
    data class Yesterday(val time: LocalTime) : MeetingDay
    data class ThisWeek(val dayOfWeek: DayOfWeek) : MeetingDay
    data class Earlier(val date: LocalDate, val sameYear: Boolean) : MeetingDay
}

sealed interface MeetingGroup {
    data object Today : MeetingGroup
    data object Yesterday : MeetingGroup
    data object ThisWeek : MeetingGroup
    // No year for the current one: "Octobre", but "Octobre 2025".
    data class InMonth(val month: Month, val year: Int?) : MeetingGroup
}

data class MeetingLength(val hours: Int, val minutes: Int)

// Days are counted on the calendar, not in 24 h blocks: 23:50 yesterday is "yesterday", even at 00:10.
fun meetingDay(startedAt: Instant, now: Instant, timeZone: TimeZone): MeetingDay {
    val start = startedAt.toLocalDateTime(timeZone)
    val today = now.toLocalDateTime(timeZone).date
    return when (start.date.daysUntil(today)) {
        0 -> MeetingDay.Today(start.time)
        1 -> MeetingDay.Yesterday(start.time)
        in 2..6 -> MeetingDay.ThisWeek(start.date.dayOfWeek)
        else -> MeetingDay.Earlier(start.date, sameYear = start.date.year == today.year)
    }
}

fun MeetingDay.group(): MeetingGroup = when (this) {
    is MeetingDay.Today -> MeetingGroup.Today
    is MeetingDay.Yesterday -> MeetingGroup.Yesterday
    is MeetingDay.ThisWeek -> MeetingGroup.ThisWeek
    is MeetingDay.Earlier -> MeetingGroup.InMonth(date.month, year = if (sameYear) null else date.year)
}

fun groupByDay(
    meetings: List<MeetingListItem>,
    now: Instant,
    timeZone: TimeZone,
): List<Pair<MeetingGroup, List<MeetingListItem>>> =
    meetings
        .sortedByDescending { it.startedAt }
        .groupBy { meetingDay(it.startedAt, now, timeZone).group() }
        .toList()

// Never "0 min": a meeting that was recorded lasted something.
fun meetingLength(duration: Duration): MeetingLength {
    val minutes = (duration + 30.seconds).inWholeMinutes.toInt().coerceAtLeast(1)
    return MeetingLength(hours = minutes / 60, minutes = minutes % 60)
}

fun clockTime(time: LocalTime): String =
    "${time.hour.twoDigits()}:${time.minute.twoDigits()}"

internal fun Int.twoDigits(): String = toString().padStart(2, '0')
