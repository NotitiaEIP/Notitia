package com.example.notitia.ui.actions

import androidx.compose.runtime.saveable.SaverScope
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.MeetingStatus
import com.example.notitia.ui.minutes.Minutes
import com.example.notitia.ui.minutes.MinutesAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class ActionsStateTest {

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
    fun aTickInTheTabShowsInTheMinutes() {
        val state = ActionsState()
        val first = state.tracked(listOf(minutes)).first()
        state.setDone(first.id, true)
        assertEquals(listOf(true, true), state.applied(minutes).actions.map { it.done })
    }

    @Test
    fun aTickInTheMinutesShowsInTheTab() {
        val state = ActionsState()
        state.setDone(ActionId("m1", 1), false)
        assertEquals(listOf(false, false), state.tracked(listOf(minutes)).map { it.action.done })
    }

    @Test
    fun eachActionKnowsItsMeetingAndItsOwnersColour() {
        val tracked = ActionsState().tracked(listOf(minutes))
        assertEquals(listOf(ActionId("m1", 0), ActionId("m1", 1)), tracked.map { it.id })
        assertEquals(listOf(meeting, meeting), tracked.map { it.meeting })
        // Adam did not speak: no colour of his own.
        assertEquals(listOf(1, null), tracked.map { it.speaker })
    }

    @Test
    fun aNewOwnerShowsInTheTabWithTheirColourAndInTheMinutes() {
        val state = ActionsState()
        state.setOwner(ActionId("m1", 1), "Lucas")
        assertEquals(listOf("Nathan", "Lucas"), state.applied(minutes).actions.map { it.owner })
        assertEquals(listOf(1, 0), state.tracked(listOf(minutes)).map { it.speaker })
    }

    @Test
    fun aRemovedOwnerOrABlankOneLeavesTheActionWithNobody() {
        val state = ActionsState()
        state.setOwner(ActionId("m1", 0), null)
        state.setOwner(ActionId("m1", 1), "  ")
        assertEquals(listOf(null, null), state.applied(minutes).actions.map { it.owner })
    }

    @Test
    fun aNewDueDateMovesTheActionToItsGroup() {
        val state = ActionsState()
        val today = LocalDate(2026, 11, 8)
        state.setDue(ActionId("m1", 0), LocalDate(2026, 11, 9))
        val action = state.tracked(listOf(minutes)).first().action
        assertEquals(DueGroup.Tomorrow, dueGroup(action.due, today))
        assertEquals(LocalDate(2026, 11, 9), state.applied(minutes).actions.first().due)
    }

    @Test
    fun theChangesSurviveARotationEvenTheRemovedOnes() {
        val state = ActionsState()
        state.setDone(ActionId("m1", 0), true)
        state.setDone(ActionId("m1", 1), false)
        state.setOwner(ActionId("m1", 0), null)
        state.setOwner(ActionId("m1", 1), "Lucas")
        state.setDue(ActionId("m1", 0), LocalDate(2026, 11, 9))
        val saved = assertNotNull(with(ActionsState.Saver) { SaverScope { true }.save(state) })
        val restored = assertNotNull(ActionsState.Saver.restore(saved)).applied(minutes).actions
        assertEquals(listOf(true, false), restored.map { it.done })
        assertEquals(listOf(null, "Lucas"), restored.map { it.owner })
        assertEquals(listOf(LocalDate(2026, 11, 9), null), restored.map { it.due })
    }

    @Test
    fun theOwnersOfferedAreTheSpeakersThenTheOtherOwnersOfTheMeeting() {
        assertEquals(listOf("Lucas", "Nathan", "Adam"), ownerChoices(minutes))
    }
}
