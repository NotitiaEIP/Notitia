package com.example.notitia.ui.meetings

import com.example.notitia.meeting.Abilities
import com.example.notitia.meeting.MeetingStatus
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.meeting
import com.example.notitia.meeting.written
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.LocalDate

class MeetingStatusTest {
    private val today = LocalDate(2026, 11, 25)
    private val all = Abilities(hear = true, write = true)

    @Test
    fun aMeetingHeardSaysHowFarAndTheTimeLeftOnceKnown() {
        val heard = meeting("recorded-1", MinutesState.Writing).copy(transcribed = 12.minutes)
        assertEquals(MeetingStatus.Transcribing(0.4f, remaining = null), heard.status(today, all))
        assertEquals(MeetingStatus.Transcribing(0.4f, 3.minutes), heard.status(today, all, remaining = 3.minutes))
    }

    // While it is recorded, the core may have heard past the duration it last told; a meeting of no sound has none.
    @Test
    fun thePartHeardStaysBetweenNothingAndAll() {
        val ahead = meeting("recorded-1", MinutesState.Writing).copy(duration = 20.seconds, transcribed = 28.seconds)
        assertEquals(MeetingStatus.Transcribing(1f), ahead.status(today, all))
        val empty = ahead.copy(duration = Duration.ZERO, transcribed = Duration.ZERO)
        assertEquals(MeetingStatus.Transcribing(0f), empty.status(today, all))
    }

    @Test
    fun whatNoEngineCanDoWaitsForAModel() {
        val heard = meeting("recorded-1", MinutesState.Writing).copy(transcribed = Duration.ZERO)
        val owed = meeting("recorded-2", MinutesState.Writing)
        assertEquals(MeetingStatus.Waiting, heard.status(today, Abilities(hear = false, write = true)))
        assertEquals(MeetingStatus.WritingMinutes, owed.status(today, Abilities(hear = false, write = true)))
        assertEquals(MeetingStatus.Waiting, owed.status(today, Abilities(hear = true, write = false)))
        assertEquals(MeetingStatus.Waiting, owed.status(today, Abilities.None))
    }

    @Test
    fun minutesSettledAreReadyWhateverTheEngines() {
        for (minutes in listOf(MinutesState.Failed, MinutesState.Unavailable, written())) {
            assertEquals(MeetingStatus.Ready(), meeting("recorded-1", minutes).status(today, Abilities.None))
        }
    }
}
