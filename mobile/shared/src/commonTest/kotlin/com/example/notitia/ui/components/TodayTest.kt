package com.example.notitia.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class TodayTest {

    private val paris = TimeZone.of("Europe/Paris")

    @Test
    fun aMinuteBeforeMidnightTheDayTurnsInAMinute() {
        val now = LocalDateTime(2026, 11, 14, 23, 59).toInstant(paris)
        assertEquals(1.minutes, untilMidnight(now, paris))
    }

    @Test
    fun atMidnightTheNextTurnIsADayAway() {
        val now = LocalDateTime(2026, 11, 14, 0, 0).toInstant(paris)
        assertEquals(24.hours, untilMidnight(now, paris))
    }

    @Test
    fun theNightTheClocksGoBackLastsAnHourMore() {
        val now = LocalDateTime(2026, 10, 25, 0, 0).toInstant(paris)
        assertEquals(25.hours, untilMidnight(now, paris))
    }
}
