package com.example.notitia.meeting

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

    // What the engines of this device can do now: a meeting that owes more waits for a model.
    val abilities: StateFlow<Abilities>

    // The lines of a meeting, read when they are shown. null when they cannot be read.
    suspend fun transcript(id: String): List<TranscriptLine>?

    fun rename(id: String, title: String)

    fun delete(ids: Set<String>)

    // Minutes that failed, or that an older app never wrote: owed again, to the core's writer.
    fun rewrite(id: String)

    fun setDone(id: ActionId, done: Boolean)

    fun setOwner(id: ActionId, owner: String?)

    fun setDue(id: ActionId, due: LocalDate?)

    fun setReminder(id: ActionId, on: Boolean)
}

// For previews, which have no core: the meetings they are given, which nothing changes.
class PreviewMeetings(shown: List<Meeting>) : Meetings {
    override val meetings: StateFlow<List<Meeting>?> = MutableStateFlow(shown)
    override val unsaved: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val abilities: StateFlow<Abilities> = MutableStateFlow(Abilities(hear = true, write = true))

    override suspend fun transcript(id: String) = meetings.value?.firstOrNull { it.id == id }?.transcript

    override fun rename(id: String, title: String) = Unit

    override fun delete(ids: Set<String>) = Unit

    override fun rewrite(id: String) = Unit

    override fun setDone(id: ActionId, done: Boolean) = Unit

    override fun setOwner(id: ActionId, owner: String?) = Unit

    override fun setDue(id: ActionId, due: LocalDate?) = Unit

    override fun setReminder(id: ActionId, on: Boolean) = Unit
}

// As the core tells it in its engines event: a kind of work no engine can do waits for a model, and nothing is marked.
data class Abilities(val hear: Boolean, val write: Boolean) {
    companion object {
        val None = Abilities(hear = false, write = false)
    }
}
