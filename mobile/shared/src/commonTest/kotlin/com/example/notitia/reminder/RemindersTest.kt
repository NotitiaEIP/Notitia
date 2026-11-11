package com.example.notitia.reminder

import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.WrittenMinutes
import com.example.notitia.storage.meeting
import com.example.notitia.ui.search.runNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class RemindersTest {

    private val paris = TimeZone.of("Europe/Paris")
    private val today = LocalDate(2026, 11, 11)
    private val now = Instant.parse("2026-11-11T10:00:00Z")

    private fun action(due: LocalDate?, reminder: Boolean = true, done: Boolean = false) =
        MinutesAction("Préparer la démo", owner = null, due = due, done = done, reminder = reminder)

    private fun meetingWith(id: String, vararg actions: MinutesAction): Meeting =
        meeting(id, MinutesState.Written(WrittenMinutes("", emptyList(), actions.toList())))

    @Test
    fun aReminderRingsAtNineTheDayBefore() {
        assertEquals(Instant.parse("2026-11-13T08:00:00Z"), reminderAt(LocalDate(2026, 11, 14), paris))
    }

    @Test
    fun theActionsDueTheSameDayShareOneReminder() {
        val due = LocalDate(2026, 11, 14)
        val meetings = listOf(
            meetingWith("a", action(due), action(LocalDate(2026, 11, 20))),
            meetingWith("b", action(due)),
        )
        val reminders = reminders(meetings, paris, today)
        assertEquals(listOf("2026-11-14", "2026-11-20"), reminders.map { it.key })
        assertEquals(listOf(ActionId("a", 0), ActionId("b", 0)), reminders.first().actions)
    }

    @Test
    fun aDoneLateOrUnaskedActionHasNoReminder() {
        val meetings = listOf(
            meetingWith(
                "a",
                action(LocalDate(2026, 11, 14), done = true),
                action(LocalDate(2026, 11, 10)),
                action(LocalDate(2026, 11, 14), reminder = false),
                action(due = null),
            ),
        )
        assertEquals(emptyList(), reminders(meetings, paris, today))
    }

    @Test
    fun aReminderDueTodayStaysSoItsNotificationStaysUp() {
        assertEquals(listOf("2026-11-11"), reminders(listOf(meetingWith("a", action(today))), paris, today).map { it.key })
    }

    private class FakeScheduler(private val left: Set<String> = emptySet()) : ReminderScheduler {
        val calls = mutableListOf<String>()
        override suspend fun pending() = left
        override fun schedule(reminder: Reminder, message: String) {
            calls += "schedule ${reminder.key}: $message"
        }
        override fun cancel(key: String) {
            calls += "cancel $key"
        }
    }

    private fun reminder(day: Int, vararg actions: Int) =
        Reminder("2026-11-$day", Instant.parse("2026-11-${day - 1}T08:00:00Z"), actions.map { ActionId("a", it) })

    private fun ReminderSync.syncNow(vararg wanted: Reminder) = runNow { sync(wanted.toList(), now) }

    @Test
    fun theFirstSyncTakesBackWhatAnEarlierRunLeft() {
        val scheduler = FakeScheduler(left = setOf("2026-11-14", "2026-11-20"))
        ReminderSync(scheduler) { "$it" }.syncNow(reminder(14, 0))
        assertEquals(listOf("cancel 2026-11-20", "schedule 2026-11-14: 1"), scheduler.calls)
    }

    @Test
    fun onlyWhatChangedIsScheduledAgain() {
        val scheduler = FakeScheduler()
        val sync = ReminderSync(scheduler) { "$it" }
        sync.syncNow(reminder(14, 0), reminder(20, 1))
        scheduler.calls.clear()

        sync.syncNow(reminder(14, 0, 2), reminder(20, 1))
        assertEquals(listOf("schedule 2026-11-14: 2"), scheduler.calls)
    }

    @Test
    fun aMovedActionMovesItsReminder() {
        val scheduler = FakeScheduler()
        val sync = ReminderSync(scheduler) { "$it" }
        sync.syncNow(reminder(14, 0))
        scheduler.calls.clear()

        sync.syncNow(reminder(20, 0))
        assertEquals(listOf("cancel 2026-11-14", "schedule 2026-11-20: 1"), scheduler.calls)
    }

    @Test
    fun aReminderPastItsTimeIsKeptButNeverScheduled() {
        val scheduler = FakeScheduler()
        val sync = ReminderSync(scheduler) { "$it" }
        sync.syncNow(reminder(12, 0))
        assertEquals(emptyList(), scheduler.calls)

        sync.syncNow()
        assertEquals(listOf("cancel 2026-11-12"), scheduler.calls)
    }
}
