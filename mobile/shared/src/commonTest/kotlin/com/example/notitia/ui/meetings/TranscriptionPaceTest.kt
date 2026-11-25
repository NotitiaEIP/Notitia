package com.example.notitia.ui.meetings

import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.meeting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TranscriptionPaceTest {
    private val start = Instant.parse("2026-11-25T10:00:00Z")

    // An hour, heard as far as told.
    private fun heard(at: Duration?, id: String = "recorded-1"): Meeting =
        meeting(id, MinutesState.Writing).copy(duration = 60.minutes, transcribed = at)

    @Test
    fun nothingIsGuessedBeforeAMeetingMoves() {
        val pace = TranscriptionPace()
            .next(listOf(heard(10.minutes)), start)
            .next(listOf(heard(10.minutes)), start + 5.seconds)
        assertNull(pace.remaining(heard(10.minutes)))
    }

    // Heard ten times faster than it was said: 30 s of audio in 3 s, 50 minutes left in 5.
    @Test
    fun theTimeLeftIsAtThePaceSeenSinceItWasLastStill() {
        val pace = TranscriptionPace()
            .next(listOf(heard(9.minutes + 30.seconds)), start)
            // Still for a while, waiting for its turn: the pace is not counted from here.
            .next(listOf(heard(9.minutes + 30.seconds)), start + 1.minutes)
            .next(listOf(heard(10.minutes)), start + 1.minutes + 3.seconds)
        assertEquals(5.minutes, pace.remaining(heard(10.minutes)))
        // Then frozen where it was first seen still: the average of each step since.
        val on = pace.next(listOf(heard(11.minutes)), start + 1.minutes + 9.seconds)
        assertEquals(4.minutes + 54.seconds, on.remaining(heard(11.minutes)))
    }

    @Test
    fun aMeetingHeardWholeIsForgottenAndOneHeardAgainStartsOver() {
        val moving = TranscriptionPace()
            .next(listOf(heard(0.minutes), heard(0.minutes, "recorded-2")), start)
            .next(listOf(heard(1.minutes), heard(0.minutes, "recorded-2")), start + 6.seconds)
        val whole = moving.next(listOf(heard(null), heard(0.minutes, "recorded-2")), start + 7.seconds)
        assertNull(whole.remaining(heard(1.minutes)))
        val again = moving.next(listOf(heard(0.minutes)), start + 8.seconds)
        assertNull(again.remaining(heard(0.minutes)))
        assertNull(again.remaining(heard(0.minutes, "recorded-2")))
    }
}
