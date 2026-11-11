package com.example.notitia.ui.actions

import com.example.notitia.meeting.ActionId
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.group_late
import notitia.shared.generated.resources.group_later
import notitia.shared.generated.resources.group_next_days
import notitia.shared.generated.resources.group_today
import notitia.shared.generated.resources.group_tomorrow
import org.jetbrains.compose.resources.StringResource

// In the order of the list: the most urgent first.
enum class DueGroup(val title: StringResource) {
    Late(Res.string.group_late),
    Today(Res.string.group_today),
    Tomorrow(Res.string.group_tomorrow),
    NextDays(Res.string.group_next_days),
    Later(Res.string.group_later),
}

private const val DAYS_IN_A_WEEK = 7

// Days are counted on the calendar, as for the meetings: an action due yesterday is late from midnight.
fun dueGroup(due: LocalDate, today: LocalDate): DueGroup {
    val days = today.daysUntil(due)
    return when {
        days < 0 -> DueGroup.Late
        days == 0 -> DueGroup.Today
        days == 1 -> DueGroup.Tomorrow
        days < DAYS_IN_A_WEEK -> DueGroup.NextDays
        else -> DueGroup.Later
    }
}

fun daysLate(due: LocalDate, today: LocalDate): Int = due.daysUntil(today)

// Nothing is due before the meeting that agreed to it, and Notitia follows a working year, not a lifetime.
fun dueDateRange(meetingDay: LocalDate, today: LocalDate): ClosedRange<LocalDate> =
    meetingDay..today.plus(DatePeriod(years = 1))

// toComplete lacks an owner or a due date, and so cannot be followed yet. open counts it with the groups, and late
// counts what the late group shows, so the line above the list always agrees with it.
data class ActionBoard(
    val toComplete: List<TrackedAction>,
    val groups: List<Pair<DueGroup, List<TrackedAction>>>,
    val done: List<TrackedAction>,
    val open: Int,
    val late: Int,
)

// A lingering action was just ticked: it keeps its place a moment before it joins the done ones.
// In a group, the soonest due first, then the earliest meeting. To complete, the earliest meeting first: it has
// waited longest.
fun actionBoard(actions: List<TrackedAction>, today: LocalDate, lingering: Set<ActionId> = emptySet()): ActionBoard {
    val (shown, done) = actions.partition { !it.action.done || it.id in lingering }
    val (toComplete, dated) = shown.partition { it.action.owner == null || it.action.due == null }
    val groups = dated
        .sortedWith(compareBy<TrackedAction> { it.action.due }.thenBy { it.meeting.startedAt })
        .groupBy { dueGroup(checkNotNull(it.action.due), today) }
        .toList()
    return ActionBoard(
        toComplete = toComplete.sortedBy { it.meeting.startedAt },
        groups = groups,
        done = done.sortedByDescending { it.meeting.startedAt },
        open = shown.size,
        late = groups.firstOrNull { it.first == DueGroup.Late }?.second?.size ?: 0,
    )
}
