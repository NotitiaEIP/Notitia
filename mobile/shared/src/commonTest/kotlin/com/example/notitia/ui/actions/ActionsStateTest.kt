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
        assertEquals(listOf(true, true), state.ticked(minutes).actions.map { it.done })
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
    fun theTicksSurviveARotation() {
        val state = ActionsState()
        state.setDone(ActionId("m1", 0), true)
        state.setDone(ActionId("m1", 1), false)
        val saved = assertNotNull(with(ActionsState.Saver) { SaverScope { true }.save(state) })
        val restored = assertNotNull(ActionsState.Saver.restore(saved))
        assertEquals(listOf(true, false), restored.ticked(minutes).actions.map { it.done })
    }
}
