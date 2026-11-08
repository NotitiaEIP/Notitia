package com.example.notitia.ui.actions

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.group_late
import notitia.shared.generated.resources.group_later
import notitia.shared.generated.resources.group_next_days
import notitia.shared.generated.resources.group_no_due
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
    NoDueDate(Res.string.group_no_due),
}

private const val DAYS_IN_A_WEEK = 7

// Days are counted on the calendar, as for the meetings: an action due yesterday is late from midnight.
fun dueGroup(due: LocalDate?, today: LocalDate): DueGroup {
    val days = today.daysUntil(due ?: return DueGroup.NoDueDate)
    return when {
        days < 0 -> DueGroup.Late
        days == 0 -> DueGroup.Today
        days == 1 -> DueGroup.Tomorrow
        days < DAYS_IN_A_WEEK -> DueGroup.NextDays
        else -> DueGroup.Later
    }
}

fun daysLate(due: LocalDate, today: LocalDate): Int = due.daysUntil(today)

// open and late count what the groups show, so the line above the list always agrees with it.
data class ActionBoard(
    val groups: List<Pair<DueGroup, List<TrackedAction>>>,
    val done: List<TrackedAction>,
    val open: Int,
    val late: Int,
)

// A lingering action was just ticked: it keeps its place a moment before it joins the done ones.
// In a group, the soonest due first, then the earliest meeting.
fun actionBoard(actions: List<TrackedAction>, today: LocalDate, lingering: Set<ActionId> = emptySet()): ActionBoard {
    val (shown, done) = actions.partition { !it.action.done || it.id in lingering }
    val groups = shown
        .sortedWith(compareBy<TrackedAction, LocalDate?>(nullsLast()) { it.action.due }.thenBy { it.meeting.startedAt })
        .groupBy { dueGroup(it.action.due, today) }
        .toList()
    return ActionBoard(
        groups = groups,
        done = done.sortedByDescending { it.meeting.startedAt },
        open = shown.size,
        late = groups.firstOrNull { it.first == DueGroup.Late }?.second?.size ?: 0,
    )
}
