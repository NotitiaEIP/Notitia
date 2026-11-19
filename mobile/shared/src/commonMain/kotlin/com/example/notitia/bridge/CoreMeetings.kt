package com.example.notitia.bridge

import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.Meetings
import com.example.notitia.meeting.TranscriptLine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

// What the screens ask of the meetings, sent to the core as values. The list itself is the session's, as the core
// tells it.
internal class CoreMeetings(private val session: CoreSession) : Meetings {
    override val meetings: StateFlow<List<Meeting>?> = session.meetings
    override val unsaved: StateFlow<Set<String>> = session.unsaved

    override suspend fun transcript(id: String): List<TranscriptLine>? = session.meeting(id)?.transcript

    override fun rename(id: String, title: String) = edit(id, MeetingEdit.Rename(title))

    override fun delete(ids: Set<String>) = session.send(Change.Delete(ids))

    override fun setDone(id: ActionId, done: Boolean) = edit(id.meetingId, MeetingEdit.SetDone(id.actionId, done))

    override fun setOwner(id: ActionId, owner: String?) = edit(id.meetingId, MeetingEdit.SetOwner(id.actionId, owner))

    override fun setDue(id: ActionId, due: LocalDate?) = edit(id.meetingId, MeetingEdit.SetDue(id.actionId, due))

    override fun setReminder(id: ActionId, on: Boolean) = edit(id.meetingId, MeetingEdit.SetReminder(id.actionId, on))

    private fun edit(meeting: String, edit: MeetingEdit) = session.send(Change.Edit(meeting, edit))
}
