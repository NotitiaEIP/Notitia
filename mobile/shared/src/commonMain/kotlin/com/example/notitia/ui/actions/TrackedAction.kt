package com.example.notitia.ui.actions

import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.minutes.Minutes
import com.example.notitia.ui.minutes.speakerOf

// An action with the meeting it was agreed in, for the list that mixes every meeting. speaker is the owner's
// colour in that meeting.
data class TrackedAction(val id: ActionId, val action: MinutesAction, val meeting: MeetingListItem, val speaker: Int?)

fun trackedActions(minutes: List<Minutes>): List<TrackedAction> = minutes.flatMap { meetingMinutes ->
    meetingMinutes.actions.mapIndexed { index, action ->
        TrackedAction(
            id = ActionId(meetingMinutes.meeting.id, index),
            action = action,
            meeting = meetingMinutes.meeting,
            speaker = meetingMinutes.speakerOf(action),
        )
    }
}

// The first names the sheet offers for an action: the meeting's speakers, then the owners of its actions. Never a
// name from another meeting (product rule 4).
fun ownerChoices(minutes: Minutes): List<String> = (minutes.speakers + minutes.actions.mapNotNull { it.owner }).distinct()
