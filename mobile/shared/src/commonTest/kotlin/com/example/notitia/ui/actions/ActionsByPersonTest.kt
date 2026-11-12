package com.example.notitia.ui.actions

import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.MeetingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

class ActionsByPersonTest {

    private val paris = TimeZone.of("Europe/Paris")

    // Monday 26 October 2026.
    private val today = LocalDate(2026, 10, 26)

    private fun inDays(days: Int) = today.plus(DatePeriod(days = days))

    private fun at(day: Int): Instant = LocalDateTime(2026, 10, day, 10, 0).toInstant(paris)

    private fun tracked(
        text: String,
        owner: String?,
        due: LocalDate? = inDays(3),
        done: Boolean = false,
        meetingAt: Instant = at(20),
        speaker: Int? = null,
    ): TrackedAction {
        val meeting = MeetingListItem(text, text, meetingAt, 30.minutes, emptyList(), MeetingStatus.Ready())
        return TrackedAction(ActionId(meeting.id, 0), MinutesAction(text, owner, due, done), meeting, speaker)
    }

    private fun List<PersonActions>.names() = map { it.name }

    @Test
    fun firstNamesMatchWhateverTheirCaseAccentsOrSpaces() {
        assertEquals("clement", personKey("  Clément "))
        assertEquals(personKey("clement"), personKey("CLÉMENT"))
        assertEquals("anne marie", personKey("Anne   Marie"))
        assertEquals("zoe", personKey("Zoé"))
    }

    @Test
    fun aPersonIsShownWithTheSpellingAndColourOfTheLatestMeeting() {
        val people = actionsByPerson(
            listOf(
                tracked("a", "clement", meetingAt = at(19), speaker = 0),
                tracked("b", "Clément", meetingAt = at(23), speaker = 2),
                tracked("c", "CLEMENT", meetingAt = at(21), speaker = 1),
            ),
            today,
        )
        assertEquals(1, people.size)
        assertEquals("Clément", people.single().name)
        assertEquals(2, people.single().speaker)
        assertEquals(3, people.single().total)
    }

    // Product rule 4: the voice never groups anyone.
    @Test
    fun peopleAreGroupedByNameNeverBySpeaker() {
        val people = actionsByPerson(
            listOf(
                tracked("a", "Nathan", speaker = 0),
                tracked("b", "Adam", speaker = 0),
                tracked("c", "Nathan", speaker = 3),
            ),
            today,
        )
        assertEquals(listOf("Nathan", "Adam"), people.names())
        assertEquals(listOf(2, 1), people.map { it.total })
    }

    @Test
    fun theMostLateComeFirstThenTheBusiestThenByName() {
        val people = actionsByPerson(
            listOf(
                tracked("1", "Zoé", due = inDays(-1)),
                tracked("2", "Adam"),
                tracked("3", "Adam"),
                tracked("4", "Lucas"),
                tracked("5", "Bruno"),
                tracked("6", "Nathan", due = inDays(-2)),
                tracked("7", "Nathan", due = inDays(-5)),
            ),
            today,
        )
        assertEquals(listOf("Nathan", "Zoé", "Adam", "Bruno", "Lucas"), people.names())
    }

    @Test
    fun theActionsNobodyOwnsCloseTheList() {
        val people = actionsByPerson(
            listOf(
                tracked("1", null, due = inDays(-3)),
                tracked("2", null),
                tracked("3", "Adam"),
            ),
            today,
        )
        assertEquals(listOf("Adam", null), people.names())
        assertEquals(null, people.last().key)
        assertEquals(2, people.last().open.size)
        assertEquals(1, people.last().late)
    }

    @Test
    fun openActionsComeSoonestDueFirstAndUndatedLast() {
        val person = actionsByPerson(
            listOf(
                tracked("sans date", "Adam", due = null),
                tracked("plus tard", "Adam", due = inDays(20)),
                tracked("faite", "Adam", due = inDays(-6), done = true),
                tracked("en retard", "Adam", due = inDays(-2)),
                tracked("demain, réunion récente", "Adam", due = inDays(1), meetingAt = at(24)),
                tracked("demain, réunion ancienne", "Adam", due = inDays(1), meetingAt = at(19)),
            ),
            today,
        ).single()
        assertEquals(
            listOf("en retard", "demain, réunion ancienne", "demain, réunion récente", "plus tard", "sans date"),
            person.open.map { it.action.text },
        )
        assertEquals(1, person.late)
        assertEquals(1, person.done)
        assertEquals(6, person.total)
    }

    @Test
    fun aJustTickedActionStaysInPlaceButCountsDone() {
        val ticked = tracked("cochée", "Adam", due = inDays(-1), done = true)
        val person = actionsByPerson(listOf(ticked, tracked("autre", "Adam")), today, lingering = setOf(ticked.id))
            .single()
        assertEquals(listOf("cochée", "autre"), person.open.map { it.action.text })
        assertEquals(1, person.late)
        assertEquals(1, person.done)
    }

    @Test
    fun someoneWithEverythingDoneKeepsACard() {
        val person = actionsByPerson(listOf(tracked("faite", "Lucas", done = true)), today).single()
        assertEquals(emptyList(), person.open)
        assertEquals(1, person.done)
        assertEquals(1, person.total)
    }

    @Test
    fun cardsKeepTheirPlaceWhileTheListIsOnScreen() {
        val before = actionsByPerson(
            listOf(tracked("1", "Nathan", due = inDays(-1)), tracked("2", "Adam"), tracked("3", null)),
            today,
        )
        assertEquals(listOf("Nathan", "Adam", null), before.names())
        val after = actionsByPerson(
            listOf(
                tracked("1", "Nathan", due = inDays(-1), done = true),
                tracked("2", "Adam"),
                tracked("3", null),
                tracked("4", "Zoé", due = inDays(-4)),
            ),
            today,
        ).keptInOrder(before.map { it.key })
        assertEquals(listOf("Nathan", "Adam", "Zoé", null), after.names())
    }

    @Test
    fun aTypedFirstNameTakesTheSpellingTheMeetingKnows() {
        val known = listOf("Clément", "Nathan")
        assertEquals("Clément", knownSpelling("clement", known))
        assertEquals("Nathan", knownSpelling(" NATHAN ", known))
        assertEquals("Zoé", knownSpelling("Zoé", known))
    }

    // Two people with the same first name are told apart by what the user wrote, such as an initial.
    @Test
    fun aFirstNameWithAnInitialIsSomeoneElse() {
        val people = actionsByPerson(listOf(tracked("1", "Clément B."), tracked("2", "Clément D.")), today)
        assertEquals(2, people.size)
    }
}
