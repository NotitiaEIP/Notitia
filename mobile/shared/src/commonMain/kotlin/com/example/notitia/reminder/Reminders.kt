package com.example.notitia.reminder

import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.MinutesState
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant

// Early enough in the day to act on it, never in the middle of the night.
private val REMINDER_TIME = LocalTime(hour = 9, minute = 0)

// One notification per due day, however many actions fall on it: key is that day.
data class Reminder(val key: String, val at: Instant, val actions: List<ActionId>)

// A touch on a reminder's notification. A class rather than a data class: touching the same one twice opens it twice.
class ReminderTap(val actionKeys: List<String>)

fun reminderAt(due: LocalDate, zone: TimeZone): Instant = due.minus(DatePeriod(days = 1)).atTime(REMINDER_TIME).toInstant(zone)

// Kept until its day is over, so a notification already shown stays up until then. A late action has none.
fun MinutesAction.hasReminder(today: LocalDate): Boolean = reminder && !done && due != null && due >= today

fun reminders(meetings: List<Meeting>, zone: TimeZone, today: LocalDate): List<Reminder> = meetings
    .flatMap { meeting ->
        val actions = (meeting.minutes as? MinutesState.Written)?.minutes?.actions.orEmpty()
        actions.mapIndexedNotNull { index, action ->
            action.due?.takeIf { action.hasReminder(today) }?.let { it to ActionId(meeting.id, index) }
        }
    }
    .groupBy({ it.first }, { it.second })
    .map { (due, actions) -> Reminder(due.toString(), reminderAt(due, zone), actions) }
    .sortedBy { it.at }

// The phone's own notifications: no server is involved (product rule 1). message never holds an action's words, since
// a notification shows on the lock screen and on a watch.
interface ReminderScheduler {
    // What an earlier run left scheduled and not yet shown.
    suspend fun pending(): Set<String>
    fun schedule(reminder: Reminder, message: String)
    // Also takes back a notification already shown.
    fun cancel(key: String)
}

// For previews: nothing rings.
object NoReminderScheduler : ReminderScheduler {
    override suspend fun pending() = emptySet<String>()
    override fun schedule(reminder: Reminder, message: String) = Unit
    override fun cancel(key: String) = Unit
}

// Brings the scheduled notifications in line with the reminders the meetings ask for, touching only what changed.
class ReminderSync(private val scheduler: ReminderScheduler, private val message: suspend (count: Int) -> String) {
    // null until the first sync, which starts from what an earlier run left: a sample meeting's reminder, say.
    private var scheduled: Map<String, Reminder?>? = null

    suspend fun sync(wanted: List<Reminder>, now: Instant) {
        val before = scheduled ?: scheduler.pending().associateWith { null }
        val after = wanted.associateBy { it.key }
        (before.keys - after.keys).forEach(scheduler::cancel)
        // A reminder whose time has passed was shown already, or is no use now: it stays, but nothing rings again.
        after.values
            .filter { before[it.key] != it && it.at > now }
            .forEach { scheduler.schedule(it, message(it.actions.size)) }
        scheduled = after
    }
}
