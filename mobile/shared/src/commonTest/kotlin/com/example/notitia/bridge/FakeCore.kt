package com.example.notitia.bridge

import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.MinutesDraft
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.WrittenMinutes

// Stands for the Rust core in the tests of what talks to it: it keeps in memory the meetings it is given and tells
// each change as the core does. The rules themselves are tested in Rust, where they live: here they go only as far as
// these tests ask.
internal open class FakeCore(vararg stored: Meeting, private val opens: Boolean = true) : NotitiaCore {
    val applied = mutableListOf<Change>()
    private val kept = stored.associateBy { it.id }.toMutableMap()
    private var listener: CoreListener? = null

    override fun open(directory: String, listener: CoreListener): CoreFailure? {
        if (!opens) return CoreFailure.Unreadable
        this.listener = listener
        tell(CoreEvent.Listed(listed()))
        return null
    }

    override fun meeting(id: String): CoreReply =
        kept[id]?.let { CoreReply(CoreJson.encodeToString(it), failure = null) } ?: CoreReply(text = null, CoreFailure.NotFound)

    override fun apply(change: String): CoreFailure? {
        val asked = try {
            CoreJson.decodeFromString<Change>(change)
        } catch (_: IllegalArgumentException) {
            return CoreFailure.Invalid
        }
        applied += asked
        when (asked) {
            is Change.Edit -> kept[asked.meeting]?.let { keep(it.edited(asked.edit)) }
            is Change.Delete -> {
                asked.meetings.forEach(kept::remove)
                tell(CoreEvent.Removed(asked.meetings))
            }
            is Change.Record -> keep(
                Meeting(
                    id = asked.id,
                    startedAt = asked.startedAt,
                    duration = asked.duration,
                    modifiedAt = asked.startedAt,
                    device = "fake",
                    title = kept[asked.id]?.title,
                    minutes = asked.minutes.settled(),
                    transcript = asked.transcript,
                ),
            )
            is Change.Lend -> {
                asked.meetings.forEach { kept.getOrPut(it.id) { it } }
                tell(CoreEvent.Listed(listed()))
            }
        }
        return null
    }

    fun tell(event: CoreEvent) = tell(CoreJson.encodeToString(CoreEvent.serializer(), event))

    fun tell(event: String) {
        listener?.onEvent(event)
    }

    private fun listed() = kept.values.sortedByDescending { it.startedAt }.map { it.copy(transcript = emptyList()) }

    private fun keep(meeting: Meeting) {
        kept[meeting.id] = meeting
        tell(CoreEvent.Changed(meeting.copy(transcript = emptyList()), at = listed().indexOfFirst { it.id == meeting.id }))
    }
}

private fun Meeting.edited(edit: MeetingEdit): Meeting = when (edit) {
    is MeetingEdit.Rename -> copy(title = edit.title)
    is MeetingEdit.SetDone -> {
        val written = (minutes as MinutesState.Written).minutes
        val actions = written.actions.map { if (it.id == edit.action) it.copy(done = edit.done) else it }
        copy(minutes = MinutesState.Written(written.copy(actions = actions)))
    }
    else -> this
}

private fun MinutesDraft.settled(): MinutesState = when (this) {
    MinutesDraft.Writing -> MinutesState.Writing
    MinutesDraft.Failed -> MinutesState.Failed
    MinutesDraft.Unavailable -> MinutesState.Unavailable
    is MinutesDraft.Written -> MinutesState.Written(
        WrittenMinutes(
            summary,
            decisions,
            actions.mapIndexed { rank, heard -> MinutesAction("drawn-$rank", heard.text, heard.owner, heard.due, done = false, heard.source) },
        ),
    )
}
