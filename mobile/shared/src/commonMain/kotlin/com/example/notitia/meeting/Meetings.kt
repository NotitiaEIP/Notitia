package com.example.notitia.meeting

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

// What the screens ask of the meetings, which the Rust core holds: each change is a value, never a function, so it
// crosses the bridge.
interface Meetings {
    // null until the files are read, so the list never flashes empty at launch. Each meeting comes without its lines.
    val meetings: StateFlow<List<Meeting>?>

    // The meetings whose file is behind what the screens show, after a write that failed: a full disk, say. The core
    // writes them again with the next change.
    val unsaved: StateFlow<Set<String>>

    // The lines of a meeting, read when they are shown. null when they cannot be read.
    suspend fun transcript(id: String): List<TranscriptLine>?

    fun rename(id: String, title: String)

    fun delete(ids: Set<String>)

    fun setDone(id: ActionId, done: Boolean)

    fun setOwner(id: ActionId, owner: String?)

    fun setDue(id: ActionId, due: LocalDate?)

    fun setReminder(id: ActionId, on: Boolean)
}

// What the recorder asks of the meetings until the core records by itself (T-58): to keep where it stands, at each
// new line, every few seconds of audio, and once the minutes are settled.
fun interface MeetingKeeper {
    fun keep(id: String, startedAt: Instant, duration: Duration, transcript: List<TranscriptLine>, minutes: MinutesDraft)
}

// For previews, which have no core: the meetings they are given, which nothing changes.
class PreviewMeetings(shown: List<Meeting>) : Meetings {
    override val meetings: StateFlow<List<Meeting>?> = MutableStateFlow(shown)
    override val unsaved: StateFlow<Set<String>> = MutableStateFlow(emptySet())

    override suspend fun transcript(id: String) = meetings.value?.firstOrNull { it.id == id }?.transcript

    override fun rename(id: String, title: String) = Unit

    override fun delete(ids: Set<String>) = Unit

    override fun setDone(id: ActionId, done: Boolean) = Unit

    override fun setOwner(id: ActionId, owner: String?) = Unit

    override fun setDue(id: ActionId, due: LocalDate?) = Unit

    override fun setReminder(id: ActionId, on: Boolean) = Unit
}
