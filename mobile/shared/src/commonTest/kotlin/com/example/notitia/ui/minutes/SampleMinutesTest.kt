package com.example.notitia.ui.minutes

import com.example.notitia.meeting.MinutesState
import com.example.notitia.ui.meetings.sampleMeetings
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

class SampleMinutesTest {

    private val now = Instant.parse("2026-10-26T09:00:00Z")

    @Test
    fun eachSampleSentenceIsSaidByASpeakerDuringItsMeeting() {
        sampleMeetings(now).forEach { meeting ->
            val actions = (meeting.minutes as? MinutesState.Written)?.minutes?.actions.orEmpty()
            actions.mapNotNull { it.source }.forEach { source ->
                assertTrue(source.speaker in meeting.speakers, source.quote)
                assertTrue(source.at < meeting.duration, source.quote)
            }
        }
    }

    @Test
    fun theTranscriptOfASampleHoldsTheSentencesItsActionsQuote() {
        sampleMeetings(now).forEach { meeting ->
            val actions = (meeting.minutes as? MinutesState.Written)?.minutes?.actions.orEmpty()
            val quotes = actions.mapNotNull { it.source?.quote }
            assertTrue(meeting.transcript.map { it.text }.containsAll(quotes), meeting.id)
        }
    }
}
