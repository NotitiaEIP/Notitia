package com.example.notitia.ui.minutes

import com.example.notitia.ui.meetings.MeetingStatus
import com.example.notitia.ui.meetings.sampleMeetings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SampleMinutesTest {

    @Test
    fun eachSampleMeetingHasTheActionsItsCardAnnounces() {
        val now = Instant.parse("2026-10-26T09:00:00Z")
        sampleMeetings(now).forEach { meeting ->
            val announced = (meeting.status as? MeetingStatus.Ready)?.actions ?: 0
            assertEquals(announced, sampleMinutes(meeting, now).actions.size, meeting.title)
        }
    }
}
