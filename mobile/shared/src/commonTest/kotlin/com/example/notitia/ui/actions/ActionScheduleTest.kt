package com.example.notitia.ui.actions

import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.ownedBy
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.MeetingStatus
import com.example.notitia.ui.meetings.sampleMinutes
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

class ActionScheduleTest {

    private val paris = TimeZone.of("Europe/Paris")

    // Monday 26 October 2026.
    private val today = LocalDate(2026, 10, 26)

    private fun inDays(days: Int) = today.plus(DatePeriod(days = days))

    private fun at(day: Int, hour: Int): Instant = LocalDateTime(2026, 10, day, hour, 0).toInstant(paris)

    private fun tracked(
        text: String,
        due: LocalDate?,
        done: Boolean = false,
        owner: String? = "Nathan",
        meetingAt: Instant = at(20, 10),
        index: Int = 0,
    ): TrackedAction {
        val meeting = MeetingListItem(text, text, meetingAt, 30.minutes, emptyList(), MeetingStatus.Ready())
        return TrackedAction(ActionId(meeting.id, index), MinutesAction(text, owner, due, done), meeting)
    }

    @Test
    fun dueDatesFallInCalendarGroups() {
        val groups = listOf(-3, -1, 0, 1, 2, 6, 7, 40).map { dueGroup(inDays(it), today) }
        assertEquals(
            listOf(
                DueGroup.Late, DueGroup.Late, DueGroup.Today, DueGroup.Tomorrow,
                DueGroup.NextDays, DueGroup.NextDays, DueGroup.Later, DueGroup.Later,
            ),
            groups,
        )
    }

    @Test
    fun lateIsCountedInDays() {
        assertEquals(1, daysLate(inDays(-1), today))
        assertEquals(9, daysLate(inDays(-9), today))
    }

    @Test
    fun aDueDateRunsFromTheMeetingDayToAYearAfterToday() {
        val range = dueDateRange(meetingDay = inDays(-3), today = today)
        assertEquals(inDays(-3), range.start)
        assertEquals(LocalDate(2027, 10, 26), range.endInclusive)
        assertEquals(false, inDays(-4) in range)
    }

    @Test
    fun groupsComeMostUrgentFirstAndTheDoneOnesApart() {
        val board = actionBoard(
            listOf(
                tracked("plus tard", inDays(30)),
                tracked("faite", inDays(-4), done = true),
                tracked("demain", inDays(1)),
                tracked("en retard", inDays(-2)),
                tracked("aujourd’hui", today),
                tracked("cette semaine", inDays(3)),
            ),
            today,
        )
        assertEquals(DueGroup.entries.toList(), board.groups.map { it.first })
        assertEquals(listOf("faite"), board.done.map { it.action.text })
        assertEquals(5, board.open)
        assertEquals(1, board.late)
    }

    @Test
    fun inAGroupTheSoonestDueComesFirstThenTheEarliestMeeting() {
        val board = actionBoard(
            listOf(
                tracked("jeudi, réunion de mardi", inDays(3), meetingAt = at(20, 14)),
                tracked("mercredi", inDays(2)),
                tracked("jeudi, réunion de lundi", inDays(3), meetingAt = at(19, 9)),
            ),
            today,
        )
        assertEquals(
            listOf("mercredi", "jeudi, réunion de lundi", "jeudi, réunion de mardi"),
            board.groups.single().second.map { it.action.text },
        )
    }

    @Test
    fun theDoneOnesShowTheLatestMeetingFirst() {
        val board = actionBoard(
            listOf(
                tracked("ancienne", null, done = true, meetingAt = at(2, 10)),
                tracked("récente", null, done = true, meetingAt = at(23, 10)),
            ),
            today,
        )
        assertEquals(listOf("récente", "ancienne"), board.done.map { it.action.text })
        assertEquals(0, board.open)
    }

    @Test
    fun aJustTickedActionKeepsItsPlaceAndItsCount() {
        val ticked = tracked("Corriger le contraste des badges", inDays(-2), done = true)
        val board = actionBoard(listOf(ticked), today, lingering = setOf(ticked.id))
        assertEquals(listOf(DueGroup.Late to listOf(ticked)), board.groups)
        assertEquals(emptyList(), board.done)
        assertEquals(1, board.late)
    }

    @Test
    fun anActionWithoutOwnerOrDueDateIsToCompleteAndCountsAsOpen() {
        val board = actionBoard(
            listOf(
                tracked("sans responsable", inDays(-2), owner = null, meetingAt = at(23, 10)),
                tracked("sans date", null, meetingAt = at(20, 10)),
                tracked("complète", inDays(1)),
                tracked("faite sans date", null, done = true),
            ),
            today,
        )
        assertEquals(listOf("sans date", "sans responsable"), board.toComplete.map { it.action.text })
        assertEquals(listOf(DueGroup.Tomorrow), board.groups.map { it.first })
        assertEquals(listOf("faite sans date"), board.done.map { it.action.text })
        assertEquals(3, board.open)
        // Late, but not in the late group: the line above the list counts what the list shows.
        assertEquals(0, board.late)
    }

    @Test
    fun aCompletedActionJoinsItsGroup() {
        val toComplete = tracked("Réserver la salle", due = null, owner = null)
        val withOwner = toComplete.copy(action = toComplete.action.ownedBy("Adam"))
        assertEquals(1, actionBoard(listOf(withOwner), today).toComplete.size)
        val board = actionBoard(listOf(withOwner.copy(action = withOwner.action.copy(due = inDays(1)))), today)
        assertEquals(emptyList(), board.toComplete)
        assertEquals(listOf(DueGroup.Tomorrow), board.groups.map { it.first })
    }

    @Test
    fun theSamplesFillEveryGroupAtAnyTimeOfDay() {
        for (now in listOf(at(26, 0).plus(5.minutes), at(26, 23).plus(55.minutes))) {
            val actions = trackedActions(sampleMinutes(now, paris))
            val board = actionBoard(actions, today)
            assertEquals(DueGroup.entries.toList(), board.groups.map { it.first })
            assertEquals(2, board.toComplete.size)
            assertEquals(8, board.open)
            assertEquals(2, board.late)
            assertEquals(1, board.done.size)
        }
    }
}
