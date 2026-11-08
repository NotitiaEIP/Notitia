package com.example.notitia.ui.actions

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.minutes.Minutes
import com.example.notitia.ui.minutes.MinutesAction
import com.example.notitia.ui.minutes.speakerOf
import kotlinx.datetime.LocalDate

// An action is known by its place in the minutes of its meeting.
data class ActionId(val meetingId: String, val index: Int) {
    // Lazy lists keep their keys in an Android Bundle, which takes a string but not this class.
    val key: String get() = "$meetingId/$index"
}

// An action with the meeting it was agreed in, for the list that mixes every meeting. speaker is the owner's
// colour in that meeting.
data class TrackedAction(val id: ActionId, val action: MinutesAction, val meeting: MeetingListItem, val speaker: Int?)

// What the user changed over what the minutes say: ticks, owners and due dates. The minutes and the Actions tab
// read the same state, so a change made on one shows on the other. Not saved when the app closes, until US-16.
@Stable
class ActionsState(
    ticks: Map<ActionId, Boolean> = emptyMap(),
    owners: Map<ActionId, String?> = emptyMap(),
    dues: Map<ActionId, LocalDate?> = emptyMap(),
) {
    private var ticks by mutableStateOf(ticks)

    // A key with a null value is an owner or a date the user removed, which is not the same as no change.
    private var owners by mutableStateOf(owners)
    private var dues by mutableStateOf(dues)

    fun setDone(id: ActionId, done: Boolean) {
        ticks += id to done
    }

    fun setOwner(id: ActionId, owner: String?) {
        owners += id to owner?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setDue(id: ActionId, due: LocalDate?) {
        dues += id to due
    }

    fun applied(minutes: Minutes): Minutes = minutes.copy(
        actions = minutes.actions.mapIndexed { index, action ->
            val id = ActionId(minutes.meeting.id, index)
            action.copy(
                done = ticks[id] ?: action.done,
                owner = if (id in owners) owners[id] else action.owner,
                due = if (id in dues) dues[id] else action.due,
            )
        },
    )

    fun tracked(minutes: List<Minutes>): List<TrackedAction> = minutes.flatMap { meetingMinutes ->
        val applied = applied(meetingMinutes)
        applied.actions.mapIndexed { index, action ->
            TrackedAction(
                id = ActionId(meetingMinutes.meeting.id, index),
                action = action,
                meeting = meetingMinutes.meeting,
                speaker = applied.speakerOf(action),
            )
        }
    }

    companion object {
        private const val FIELDS_PER_CHANGE = 3

        // Dates are kept as text: an Android Bundle cannot hold a LocalDate.
        val Saver: Saver<ActionsState, Any> = listSaver<ActionsState, List<Any?>>(
            save = { state ->
                listOf(state.ticks.flat { it }, state.owners.flat { it }, state.dues.flat { it?.toString() })
            },
            restore = { (ticks, owners, dues) ->
                ActionsState(
                    ticks = ticks.unflat { it as Boolean },
                    owners = owners.unflat { it as String? },
                    dues = dues.unflat { (it as String?)?.let(LocalDate::parse) },
                )
            },
        )

        private fun <T> Map<ActionId, T>.flat(value: (T) -> Any?): List<Any?> =
            flatMap { (id, change) -> listOf(id.meetingId, id.index, value(change)) }

        private fun <T> List<Any?>.unflat(value: (Any?) -> T): Map<ActionId, T> =
            chunked(FIELDS_PER_CHANGE).associate { (meetingId, index, change) ->
                ActionId(meetingId as String, index as Int) to value(change)
            }
    }
}

// The first names the sheet offers for an action: the meeting's speakers, then the owners of its actions. Never a
// name from another meeting (product rule 4).
fun ownerChoices(minutes: Minutes): List<String> = (minutes.speakers + minutes.actions.mapNotNull { it.owner }).distinct()
