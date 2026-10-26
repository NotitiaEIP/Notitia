package com.example.notitia.ui.meetings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class MeetingFormatTest {

    private val paris = TimeZone.of("Europe/Paris")

    // Monday 26 October 2026, 10:00 in Paris.
    private val now = at(2026, 10, 26, 10, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        LocalDateTime(year, month, day, hour, minute).toInstant(paris)

    private fun meeting(id: String, startedAt: Instant) =
        MeetingListItem(id, id, startedAt, 30.minutes, 2, MeetingStatus.Ready(actions = 0))

    @Test
    fun sameDayIsTodayWithItsTime() {
        assertEquals(MeetingDay.Today(LocalTime(8, 5)), meetingDay(at(2026, 10, 26, 8, 5), now, paris))
    }

    @Test
    fun dayBeforeIsYesterdayEvenJustAfterMidnight() {
        val justAfterMidnight = at(2026, 10, 26, 0, 10)
        assertEquals(
            MeetingDay.Yesterday(LocalTime(23, 50)),
            meetingDay(at(2026, 10, 25, 23, 50), justAfterMidnight, paris),
        )
    }

    @Test
    fun upToSixDaysAgoShowsTheWeekday() {
        assertEquals(MeetingDay.ThisWeek(DayOfWeek.TUESDAY), meetingDay(at(2026, 10, 20, 9, 0), now, paris))
    }

    @Test
    fun sevenDaysAgoShowsTheDate() {
        assertEquals(
            MeetingDay.Earlier(LocalDate(2026, 10, 19), sameYear = true),
            meetingDay(at(2026, 10, 19, 9, 0), now, paris),
        )
    }

    @Test
    fun dayDependsOnTheTimeZone() {
        val lateInParis = at(2026, 10, 25, 23, 30)
        assertEquals(MeetingDay.Yesterday(LocalTime(23, 30)), meetingDay(lateInParis, now, paris))
        assertEquals(
            MeetingDay.Today(LocalTime(22, 30)),
            meetingDay(lateInParis, at(2026, 10, 25, 23, 45), TimeZone.UTC),
        )
    }

    @Test
    fun monthGroupShowsTheYearOnlyForAnotherYear() {
        assertEquals(
            MeetingGroup.InMonth(Month.OCTOBER, year = null),
            MeetingDay.Earlier(LocalDate(2026, 10, 3), sameYear = true).group(),
        )
        assertEquals(
            MeetingGroup.InMonth(Month.OCTOBER, year = 2025),
            MeetingDay.Earlier(LocalDate(2025, 10, 3), sameYear = false).group(),
        )
    }

    @Test
    fun meetingsAreGroupedByDayMostRecentFirst() {
        val groups = groupByDay(
            listOf(
                meeting("september", at(2026, 9, 14, 9, 0)),
                meeting("morning", at(2026, 10, 26, 8, 0)),
                meeting("tuesday", at(2026, 10, 20, 9, 0)),
                meeting("afternoon", at(2026, 10, 26, 9, 30)),
                meeting("last-year", at(2025, 9, 2, 9, 0)),
            ),
            now,
            paris,
        )
        assertEquals(
            listOf(
                MeetingGroup.Today to listOf("afternoon", "morning"),
                MeetingGroup.ThisWeek to listOf("tuesday"),
                MeetingGroup.InMonth(Month.SEPTEMBER, year = null) to listOf("september"),
                MeetingGroup.InMonth(Month.SEPTEMBER, year = 2025) to listOf("last-year"),
            ),
            groups.map { (group, meetings) -> group to meetings.map { it.id } },
        )
    }

    @Test
    fun lengthRoundsToTheMinuteAndNeverShowsZero() {
        assertEquals(MeetingLength(0, 1), meetingLength(10.seconds))
        assertEquals(MeetingLength(0, 45), meetingLength(45.minutes + 20.seconds))
        assertEquals(MeetingLength(1, 5), meetingLength(65.minutes))
        assertEquals(MeetingLength(2, 0), meetingLength(2.hours))
    }

    @Test
    fun clockTimeKeepsTwoDigits() {
        assertEquals("09:05", clockTime(LocalTime(9, 5)))
        assertEquals("14:30", clockTime(LocalTime(14, 30)))
    }
}
