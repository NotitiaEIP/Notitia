package com.example.notitia.ui.actions

import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.MeetingStatus
import com.example.notitia.ui.meetings.sampleMeetings
import com.example.notitia.ui.minutes.MinutesAction
import com.example.notitia.ui.minutes.sampleMinutes
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
        meetingAt: Instant = at(20, 10),
        index: Int = 0,
    ): TrackedAction {
        val meeting = MeetingListItem(text, text, meetingAt, 30.minutes, 2, MeetingStatus.Ready(actions = 1))
        return TrackedAction(ActionId(meeting.id, index), MinutesAction(text, "Nathan", due, done), meeting, speaker = null)
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
        assertEquals(DueGroup.NoDueDate, dueGroup(null, today))
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
                tracked("sans date", null),
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
        assertEquals(6, board.open)
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
    fun theSamplesFillEveryGroupAtAnyTimeOfDay() {
        for (now in listOf(at(26, 0).plus(5.minutes), at(26, 23).plus(55.minutes))) {
            val actions = ActionsState().tracked(sampleMeetings(now, paris).map { sampleMinutes(it, now, paris) })
            val board = actionBoard(actions, today)
            assertEquals(DueGroup.entries.toList(), board.groups.map { it.first })
            assertEquals(7, board.open)
            assertEquals(2, board.late)
            assertEquals(2, board.done.size)
        }
    }
}
