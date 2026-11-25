package com.example.notitia.ui.minutes

import com.example.notitia.meeting.Abilities
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.TranscriptLine
import com.example.notitia.meeting.WrittenMinutes
import com.example.notitia.meeting.meeting
import com.example.notitia.meeting.written
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MinutesNoticeTest {
    private val all = Abilities(hear = true, write = true)
    private val empty = MinutesState.Written(WrittenMinutes(summary = " ", decisions = emptyList(), actions = emptyList()))

    @Test
    fun eachStateSaysWhereTheMinutesStandAndOnlyThoseThatEndedUnwrittenCanBeAskedAgain() {
        val heard = meeting("recorded-1", MinutesState.Writing).copy(transcribed = Duration.ZERO)
        val owed = meeting("recorded-1", MinutesState.Writing)
        val cases = listOf(
            heard.minutesNotice(all, false) to MinutesNotice.Hearing,
            heard.minutesNotice(Abilities(hear = false, write = true), false) to MinutesNotice.WaitingToHear,
            owed.minutesNotice(all, false) to MinutesNotice.Writing,
            owed.minutesNotice(Abilities(hear = true, write = false), false) to MinutesNotice.WaitingToWrite,
            meeting("recorded-1", MinutesState.Failed).minutesNotice(Abilities.None, false) to MinutesNotice.Failed,
            meeting("recorded-1", MinutesState.Unavailable).minutesNotice(Abilities.None, false) to MinutesNotice.NeverWritten,
        )
        cases.forEach { (notice, expected) -> assertEquals(expected, notice) }
        val askable = MinutesNotice.entries.filter { it.rewrite != null }
        assertEquals(listOf(MinutesNotice.Failed, MinutesNotice.NeverWritten), askable)
    }

    @Test
    fun minutesWrittenOfNothingHeardSaySoOnlyWhenEveryPassageFailed() {
        assertEquals(MinutesNotice.NothingHeard, meeting("recorded-1", empty).minutesNotice(all, everyPassageFailed = true))
        assertNull(meeting("recorded-1", empty).minutesNotice(all, everyPassageFailed = false))
        assertNull(meeting("recorded-1", written("Préparer la démo")).minutesNotice(all, everyPassageFailed = true))
    }

    @Test
    fun aPassageFailedIsOneWithoutText() {
        val failed = TranscriptLine(Duration.ZERO, text = null)
        assertTrue(everyPassageFailed(listOf(failed, TranscriptLine(30.seconds, " "))))
        assertFalse(everyPassageFailed(listOf(failed, TranscriptLine(30.seconds, "Bonjour à tous."))))
        // Nothing heard at all is a silent meeting, not a failure.
        assertFalse(everyPassageFailed(emptyList()))
    }
}
