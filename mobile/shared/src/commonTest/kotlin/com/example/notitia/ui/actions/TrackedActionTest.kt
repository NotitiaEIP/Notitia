package com.example.notitia.ui.actions

import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.ownedBy
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.MeetingStatus
import com.example.notitia.ui.minutes.Minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TrackedActionTest {

    private val meeting = MeetingListItem(
        "m1", "Revue de sprint 1", Instant.parse("2026-10-23T08:30:00Z"), 65.minutes, 4, MeetingStatus.Ready(actions = 2),
    )

    private val minutes = Minutes(
        meeting = meeting,
        speakers = listOf("Lucas", "Nathan"),
        decisions = emptyList(),
        actions = listOf(
            MinutesAction("Corriger le contraste des badges", "Nathan", due = null, done = false),
            MinutesAction("Fixer la date de la revue", "Adam", due = null, done = true),
        ),
        summary = "",
    )

    @Test
    fun eachActionKnowsItsMeetingAndItsOwnersColour() {
        val tracked = trackedActions(listOf(minutes))
        assertEquals(listOf(ActionId("m1", 0), ActionId("m1", 1)), tracked.map { it.id })
        assertEquals(listOf(meeting, meeting), tracked.map { it.meeting })
        // Adam did not speak: no colour of his own.
        assertEquals(listOf(1, null), tracked.map { it.speaker })
    }

    @Test
    fun aNewOwnerWhoSpokeTakesTheirColour() {
        val changed = minutes.copy(actions = minutes.actions.map { it.ownedBy("Lucas") })
        assertEquals(listOf(0, 0), trackedActions(listOf(changed)).map { it.speaker })
    }

    @Test
    fun aBlankOwnerOrOneWithSpacesIsTrimmed() {
        val action = minutes.actions.first()
        assertEquals(null, action.ownedBy("  ").owner)
        assertEquals(null, action.ownedBy(null).owner)
        assertEquals("Lucas", action.ownedBy(" Lucas ").owner)
    }

    @Test
    fun theOwnersOfferedAreTheSpeakersThenTheOtherOwnersOfTheMeeting() {
        assertEquals(listOf("Lucas", "Nathan", "Adam"), ownerChoices(minutes))
    }
}
