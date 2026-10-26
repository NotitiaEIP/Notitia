package com.example.notitia.ui.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RecordingFormatTest {

    @Test
    fun timerShowsMinutesAndSecondsUnderAnHour() {
        assertEquals("00:00", timerText(Duration.ZERO))
        assertEquals("00:59", timerText(59.seconds))
        assertEquals("12:34", timerText(12.minutes + 34.seconds))
        assertEquals("59:59", timerText(59.minutes + 59.seconds))
    }

    @Test
    fun timerAddsHoursFromTheFirstHour() {
        assertEquals("1:00:00", timerText(1.hours))
        assertEquals("1:02:03", timerText(1.hours + 2.minutes + 3.seconds))
        assertEquals("10:00:00", timerText(10.hours))
    }

    @Test
    fun waveStaysBetweenRestAndFullHeight() {
        for (phase in listOf(0f, 0.4f, 1.7f, 12.3f)) {
            repeat(WAVE_BARS) { index ->
                val level = waveLevel(index, phase)
                assertTrue(level in WAVE_REST_LEVEL..1f, "bar $index at $phase: $level")
            }
        }
    }
}
