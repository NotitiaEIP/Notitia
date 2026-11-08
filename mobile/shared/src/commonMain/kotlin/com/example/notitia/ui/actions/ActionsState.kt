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

// An action is known by its place in the minutes of its meeting.
data class ActionId(val meetingId: String, val index: Int) {
    // Lazy lists keep their keys in an Android Bundle, which takes a string but not this class.
    val key: String get() = "$meetingId/$index"
}

// An action with the meeting it was agreed in, for the list that mixes every meeting. speaker is the owner's
// colour in that meeting.
data class TrackedAction(val id: ActionId, val action: MinutesAction, val meeting: MeetingListItem, val speaker: Int?)

// What the user ticked or unticked, over what the minutes say. The minutes and the Actions tab read the same
// state, so an action ticked on one shows ticked on the other. Not saved when the app closes, until US-16.
@Stable
class ActionsState(ticks: Map<ActionId, Boolean> = emptyMap()) {
    private var ticks by mutableStateOf(ticks)

    fun setDone(id: ActionId, done: Boolean) {
        ticks += id to done
    }

    fun ticked(minutes: Minutes): Minutes = minutes.copy(
        actions = minutes.actions.mapIndexed { index, action ->
            action.copy(done = ticks[ActionId(minutes.meeting.id, index)] ?: action.done)
        },
    )

    fun tracked(minutes: List<Minutes>): List<TrackedAction> = minutes.flatMap { meetingMinutes ->
        ticked(meetingMinutes).actions.mapIndexed { index, action ->
            TrackedAction(
                id = ActionId(meetingMinutes.meeting.id, index),
                action = action,
                meeting = meetingMinutes.meeting,
                speaker = meetingMinutes.speakerOf(action),
            )
        }
    }

    companion object {
        private const val FIELDS_PER_TICK = 3

        val Saver: Saver<ActionsState, Any> = listSaver(
            save = { state -> state.ticks.flatMap { (id, done) -> listOf(id.meetingId, id.index, done) } },
            restore = { saved ->
                ActionsState(
                    saved.chunked(FIELDS_PER_TICK).associate { (meetingId, index, done) ->
                        ActionId(meetingId as String, index as Int) to done as Boolean
                    },
                )
            },
        )
    }
}
