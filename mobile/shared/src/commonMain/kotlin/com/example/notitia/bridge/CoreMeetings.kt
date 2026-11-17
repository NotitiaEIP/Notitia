package com.example.notitia.bridge

import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MeetingKeeper
import com.example.notitia.meeting.Meetings
import com.example.notitia.meeting.MinutesDraft
import com.example.notitia.meeting.TranscriptLine
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

// The order of the core, which tells a new meeting without its place in the list.
private val latestFirst = compareByDescending<Meeting> { it.startedAt }.thenBy { it.id }

// The meetings as the core holds them: a change goes in, an event comes back, the screens follow. Nothing is changed
// here, where no rule lives: a tick shows at once because the core tells it before it writes the file.
class CoreMeetings(
    private val core: NotitiaCore,
    directory: String,
    // Shown and changed like the others, never written: they are not the user's meetings.
    samples: List<Meeting> = emptyList(),
    // One call at a time and in order, off the main thread: the core writes the file before it returns. Not tied to
    // a screen: closing the app must not cancel a write.
    private val calls: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : Meetings, MeetingKeeper {
    private val scope = CoroutineScope(SupervisorJob() + calls)
    private val shown = MutableStateFlow<List<Meeting>?>(null)
    private val behind = MutableStateFlow(emptySet<String>())

    // Touched only during a call to the core, which tells its events before it returns: one call runs at a time, so
    // these need no lock.
    private var told = emptyList<Meeting>()
    private var opened = false
    private var listed = false

    override val meetings: StateFlow<List<Meeting>?> = shown.asStateFlow()
    override val unsaved: StateFlow<Set<String>> = behind.asStateFlow()

    init {
        scope.launch {
            opened = core.open(directory) { follow(it) } == null
            if (opened && samples.isNotEmpty()) apply(Change.Lend(samples))
            // Shown once, the samples in: the list does not grow under the eye at launch. A folder that cannot be
            // read must not hold the app on its splash screen: it then starts with no meeting.
            listed = true
            shown.value = told
        }
    }

    override suspend fun transcript(id: String): List<TranscriptLine>? = withContext(calls) {
        try {
            core.meeting(id).text?.let { CoreJson.decodeFromString<Meeting>(it).transcript }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun rename(id: String, title: String) = edit(id, MeetingEdit.Rename(title))

    override fun delete(ids: Set<String>) = send(Change.Delete(ids))

    override fun setDone(id: ActionId, done: Boolean) = edit(id.meetingId, MeetingEdit.SetDone(id.actionId, done))

    override fun setOwner(id: ActionId, owner: String?) = edit(id.meetingId, MeetingEdit.SetOwner(id.actionId, owner))

    override fun setDue(id: ActionId, due: LocalDate?) = edit(id.meetingId, MeetingEdit.SetDue(id.actionId, due))

    override fun setReminder(id: ActionId, on: Boolean) = edit(id.meetingId, MeetingEdit.SetReminder(id.actionId, on))

    override fun keep(
        id: String,
        startedAt: Instant,
        duration: Duration,
        transcript: List<TranscriptLine>,
        minutes: MinutesDraft,
    ) = send(Change.Record(id, startedAt, duration, transcript, minutes))

    private fun edit(meeting: String, edit: MeetingEdit) = send(Change.Edit(meeting, edit))

    private fun send(change: Change) {
        scope.launch { if (opened) apply(change) }
    }

    private fun apply(change: Change) {
        core.apply(CoreJson.encodeToString(Change.serializer(), change))
    }

    // Catching IllegalArgumentException covers SerializationException: an event this app cannot read is skipped,
    // never thrown back into the core.
    private fun follow(event: String) {
        val read = try {
            CoreJson.decodeFromString<CoreEvent>(event)
        } catch (_: IllegalArgumentException) {
            return
        }
        when (read) {
            is CoreEvent.Listed -> told = read.meetings
            is CoreEvent.Changed -> told = (told.filterNot { it.id == read.meeting.id } + read.meeting).sortedWith(latestFirst)
            is CoreEvent.Removed -> told = told.filterNot { it.id in read.meetings }
            is CoreEvent.Unsaved -> behind.value = read.meetings
        }
        if (listed) shown.value = told
    }
}
