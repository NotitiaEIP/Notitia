package com.example.notitia.storage

import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.renamed
import com.example.notitia.meeting.withAction
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The one place that holds the meetings. A change shows at once; the file follows in the background.
class MeetingRepository(
    private val store: MeetingStore,
    // Shown and changed like the others, never written: they are not the user's meetings.
    private val samples: List<Meeting> = emptyList(),
    private val clock: Clock = Clock.System,
    // One write at a time and in order, so a tick never overwrites the end of a transcription. Not tied to a
    // screen: closing the app must not cancel a write.
    disk: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val writes = CoroutineScope(SupervisorJob() + disk)
    private val sampleIds = samples.map { it.id }.toSet()
    private val state = MutableStateFlow<List<Meeting>?>(null)

    // Deleted while the app runs: a stopped meeting is saved until its minutes are written, and must not come back.
    private val deleted = MutableStateFlow(emptySet<String>())

    // null until the files are read, so the list never flashes empty at launch.
    val meetings: StateFlow<List<Meeting>?> = state.asStateFlow()

    @Volatile
    var device: String = ""
        private set

    init {
        writes.launch {
            // An unreadable folder must not hold the app on its splash screen: it then starts with no meeting.
            val stored = runCatching {
                device = store.device()
                store.loadAll().map { it.interrupted() }
            }.getOrDefault(emptyList())
            // A meeting saved while the files were being read wins over its older copy.
            state.update { saved ->
                (saved.orEmpty() + stored + samples).distinctBy { it.id }.filterNot { it.id in deleted.value }
            }
        }
    }

    fun save(meeting: Meeting) {
        state.update { meetings ->
            val others = meetings.orEmpty()
            when {
                meeting.id in deleted.value -> meetings
                others.none { it.id == meeting.id } -> listOf(meeting) + others
                else -> others.map { if (it.id == meeting.id) meeting else it }
            }
        }
        // A failed write, a full disk say, keeps the meeting on screen: its next change writes it again. Checked again
        // on the disk's turn, since a delete may have come in between.
        if (meeting.id !in sampleIds) {
            writes.launch { if (meeting.id !in deleted.value) runCatching { store.save(meeting) } }
        }
    }

    fun updateAction(id: ActionId, transform: (MinutesAction) -> MinutesAction) =
        update(id.meetingId) { it.withAction(id.index, transform) }

    fun rename(id: String, title: String) = update(id) { meeting -> meeting.renamed(title).takeIf { it != meeting } }

    // For good, samples included until the next launch. One change for all of them, so the list closes up once;
    // the reminders follow the list, so the meetings' go too.
    fun delete(ids: Set<String>) {
        deleted.update { it + ids }
        state.update { meetings -> meetings?.filterNot { it.id in ids } }
        val written = ids - sampleIds
        if (written.isNotEmpty()) writes.launch { written.forEach { runCatching { store.delete(it) } } }
    }

    // A change made by the user is dated, so two devices can be reconciled later (E10). null leaves it as it was.
    private fun update(id: String, change: (Meeting) -> Meeting?) {
        val meeting = state.value?.firstOrNull { it.id == id } ?: return
        change(meeting)?.let { save(it.copy(modifiedAt = clock.now())) }
    }
}

// Minutes left writing belong to an app that was killed: without the audio, nothing can resume them.
private fun Meeting.interrupted(): Meeting =
    if (minutes == MinutesState.Writing) copy(minutes = MinutesState.Failed) else this
