package com.example.notitia.ui.actions

import com.example.notitia.meeting.ActionBoard
import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.DueGroup
import com.example.notitia.meeting.Grouped
import com.example.notitia.meeting.MeetingStatus
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.Placed
import com.example.notitia.meeting.PlacedPerson
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.minutes.Minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class TrackedActionTest {

    private val meeting = MeetingListItem(
        "m1", "Revue de sprint 1", Instant.parse("2026-10-23T08:30:00Z"), 65.minutes, emptyList(), MeetingStatus.Ready(),
    )

    private val minutes = Minutes(
        meeting = meeting,
        speakers = listOf("Lucas", "Nathan"),
        decisions = emptyList(),
        actions = listOf(
            MinutesAction("0", "Corriger le contraste des badges", "Nathan", due = null, done = false),
            MinutesAction("6f1c2d3e", "Fixer la date de la revue", "Adam", due = null, done = true),
        ),
        summary = "",
    )

    @Test
    fun eachActionKnowsItsMeeting() {
        val tracked = trackedActions(listOf(minutes))
        assertEquals(listOf(ActionId("m1", "0"), ActionId("m1", "6f1c2d3e")), tracked.map { it.id })
        assertEquals(listOf(meeting, meeting), tracked.map { it.meeting })
    }

    @Test
    fun theOwnersOfferedAreTheSpeakersThenTheOtherOwnersOfTheMeeting() {
        assertEquals(listOf("Lucas", "Nathan", "Adam"), ownerChoices(minutes))
    }

    private val today = LocalDate(2026, 10, 26)

    private fun board(
        toComplete: List<Placed> = emptyList(),
        groups: List<Grouped> = emptyList(),
        done: List<Placed> = emptyList(),
        people: List<PlacedPerson> = emptyList(),
    ) = ActionBoard(today, toComplete, groups, done, open = 1, late = 1, people, emptyMap(), meetings = emptyList())

    @Test
    fun eachPlaceShowsTheActionItNamesWithWhatTheDayDecidedOfIt() {
        val late = Placed("m1", "0", DueGroup.Late, daysLate = 2)
        val lists = board(
            groups = listOf(Grouped(DueGroup.Late, listOf(late))),
            done = listOf(Placed("m1", "6f1c2d3e")),
            people = listOf(PlacedPerson("nathan", "Nathan", listOf(late), late = 1, done = 0, total = 1)),
        ).lists(listOf(minutes))
        val shown = TrackedAction(ActionId("m1", "0"), minutes.actions[0], meeting, DueGroup.Late, daysLate = 2)
        assertEquals(listOf(DueGroup.Late to listOf(shown)), lists.groups)
        assertEquals(listOf(trackedActions(listOf(minutes))[1]), lists.done)
        assertEquals(listOf(PersonActions("nathan", "Nathan", listOf(shown), late = 1, done = 0, total = 1)), lists.people)
        assertEquals(Triple(today, 1, 1), Triple(lists.today, lists.open, lists.late))
        assertEquals(listOf(shown, lists.done.single()), lists.all)
    }

    // As the core does: a damaged file holding an identifier twice is reached at its first action.
    @Test
    fun aPlaceNamesTheFirstActionOfItsIdentifierInItsMeeting() {
        val twice = minutes.copy(actions = minutes.actions + minutes.actions[0].copy(text = "Une autre"))
        val lists = board(toComplete = listOf(Placed("m1", "0"))).lists(listOf(twice))
        assertEquals(listOf("Corriger le contraste des badges"), lists.toComplete.map { it.action.text })
    }

    // Its meeting gone, or not told yet: nothing is shown in its place, and no group stays without a card.
    @Test
    fun aPlaceWithoutItsActionIsLeftOut() {
        val lists = board(
            toComplete = listOf(Placed("m2", "0")),
            groups = listOf(Grouped(DueGroup.Today, listOf(Placed("m1", "9", DueGroup.Today)))),
        ).lists(listOf(minutes))
        assertTrue(lists.toComplete.isEmpty() && lists.groups.isEmpty())
    }
}
