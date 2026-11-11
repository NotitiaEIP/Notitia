package com.example.notitia.storage

import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.MinutesState
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
            state.update { saved -> (saved.orEmpty() + stored + samples).distinctBy { it.id } }
        }
    }

    fun save(meeting: Meeting) {
        state.update { meetings ->
            val others = meetings.orEmpty()
            if (others.none { it.id == meeting.id }) listOf(meeting) + others
            else others.map { if (it.id == meeting.id) meeting else it }
        }
        // A failed write, a full disk say, keeps the meeting on screen: its next change writes it again.
        if (meeting.id !in sampleIds) writes.launch { runCatching { store.save(meeting) } }
    }

    fun updateAction(id: ActionId, transform: (MinutesAction) -> MinutesAction) {
        val meeting = state.value?.firstOrNull { it.id == id.meetingId } ?: return
        save(meeting.withAction(id.index, transform).copy(modifiedAt = clock.now()))
    }
}

// Minutes left writing belong to an app that was killed: without the audio, nothing can resume them.
private fun Meeting.interrupted(): Meeting =
    if (minutes == MinutesState.Writing) copy(minutes = MinutesState.Failed) else this
