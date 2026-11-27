package com.example.notitia.ui.actions

import com.example.notitia.meeting.ActionBoard
import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.DueGroup
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.Placed
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.minutes.Minutes
import kotlinx.datetime.LocalDate

// An action with the meeting it was agreed in, for the list that mixes every meeting. group and daysLate are what
// the core decided of it for the day of the board: null without a due date, daysLate only while late and not done.
data class TrackedAction(
    val id: ActionId,
    val action: MinutesAction,
    val meeting: MeetingListItem,
    val group: DueGroup? = null,
    val daysLate: Int? = null,
)

fun trackedActions(minutes: List<Minutes>): List<TrackedAction> = minutes.flatMap { meetingMinutes ->
    meetingMinutes.actions.map { action ->
        TrackedAction(
            id = ActionId(meetingMinutes.meeting.id, action.id),
            action = action,
            meeting = meetingMinutes.meeting,
        )
    }
}

// The first names the sheet offers for an action: the meeting's speakers, then the owners of its actions. Never a
// name from another meeting (product rule 4).
fun ownerChoices(minutes: Minutes): List<String> = (minutes.speakers + minutes.actions.mapNotNull { it.owner }).distinct()

// What the Actions tab shows: the board of the core, each place with the action it names. today is the board's, so
// every date on the screen counts from the day its places were made for.
data class ActionLists(
    val today: LocalDate,
    val toComplete: List<TrackedAction>,
    val groups: List<Pair<DueGroup, List<TrackedAction>>>,
    val done: List<TrackedAction>,
    val open: Int,
    val late: Int,
    val people: List<PersonActions>,
) {
    val all: List<TrackedAction> get() = toComplete + groups.flatMap { it.second } + done
}

// minutes are those of the board's own meetings, so a place and its text are of the same instant. A place names the
// first action of its identifier in its meeting, as the core does (ADR 0017, point 2); one whose action is missing is
// left out.
fun ActionBoard.lists(minutes: List<Minutes>): ActionLists {
    val actions = trackedActions(minutes).distinctBy { it.id }.associateBy { it.id }
    fun List<Placed>.tracked() = mapNotNull { placed ->
        actions[placed.id]?.copy(group = placed.group, daysLate = placed.daysLate)
    }
    return ActionLists(
        today = today,
        toComplete = toComplete.tracked(),
        groups = groups.map { it.group to it.actions.tracked() }.filter { (_, actions) -> actions.isNotEmpty() },
        done = done.tracked(),
        open = open,
        late = late,
        people = people.map { PersonActions(it.key, it.name, it.open.tracked(), it.late, it.done, it.total) },
    )
}
