package com.example.notitia.ui.recording

import com.example.notitia.ai.audio
import com.example.notitia.recording.loudness
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
    fun aBarStaysBetweenRestAndFullHeight() {
        assertEquals(WAVE_REST_LEVEL, waveBarLevel(0f))
        assertEquals(1f, waveBarLevel(1f))
        assertEquals(1f, waveBarLevel(3f))
        assertTrue(waveBarLevel(0.5f) in WAVE_REST_LEVEL..1f)
    }

    @Test
    fun aShoutStandsWellAboveAVoice() {
        // About -20 dBFS for a voice at arm's length, -8 dBFS for a shout.
        val voice = waveBarLevel(loudness(audio(100, level = 3_300)))
        val shout = waveBarLevel(loudness(audio(100, level = 13_000)))
        assertTrue(shout > voice * 1.4f, "voice: $voice, shout: $shout")
        assertTrue(voice in 0.5f..0.7f, "voice: $voice")
    }
}
