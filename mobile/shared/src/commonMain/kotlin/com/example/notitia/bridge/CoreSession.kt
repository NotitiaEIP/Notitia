package com.example.notitia.bridge

import com.example.notitia.meeting.Meeting
import kotlin.concurrent.Volatile
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
import kotlinx.coroutines.withContext

// The core as this app holds it: opened once, its calls made one at a time and in order, its events read once into
// the state they tell. The screens reach it through facades that keep no state of their own, CoreMeetings first: a new
// kind of event changes only the facade that follows it. Nothing is changed here, where no rule lives: a tick shows at
// once because the core tells it before it writes the file.
internal class CoreSession(
    private val core: NotitiaCore,
    directory: String,
    // The key of the app, which only the platform can keep. Read once, off the main thread, and wiped once the core
    // holds it. null: the meetings open, but nothing is recorded.
    key: () -> ByteArray? = { null },
    // Shown and changed like the others, never written: they are not the user's meetings.
    samples: List<Meeting> = emptyList(),
    // One call at a time and in order, off the main thread: the core writes the file before it returns. Not tied to
    // a screen: closing the app must not cancel a write.
    private val calls: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val scope = CoroutineScope(SupervisorJob() + calls)
    private val shown = MutableStateFlow<List<Meeting>?>(null)
    private val behind = MutableStateFlow(emptySet<String>())

    // The core tells the meetings one event at a time, from the thread of a call or from the recorder's: the list
    // changes by compare-and-set, so no change is lost between two threads.
    private val told = MutableStateFlow(emptyList<Meeting>())

    @Volatile
    private var opened = false

    @Volatile
    private var listed = false

    // null until the files are read, so the list never flashes empty at launch. Each meeting comes without its lines.
    val meetings: StateFlow<List<Meeting>?> = shown.asStateFlow()

    // The meetings whose file is behind what the screens show, after a write that failed.
    val unsaved: StateFlow<Set<String>> = behind.asStateFlow()

    init {
        scope.launch {
            // A key that cannot be read, or that is no key, must not hold the app on its splash screen either: the
            // meetings open, and nothing is recorded.
            val given = try {
                key()
            } catch (_: Exception) {
                null
            }
            opened = core.open(directory, given?.takeIf { it.size == KEY_BYTES }) { follow(it) } == null
            given?.fill(0)
            if (opened && samples.isNotEmpty()) apply(Change.Lend(samples))
            // Shown once, the samples in: the list does not grow under the eye at launch. A folder that cannot be
            // read must not hold the app on its splash screen: it then starts with no meeting.
            listed = true
            shown.update { told.value }
        }
    }

    // After every call asked before it.
    fun send(request: CoreRequest) {
        scope.launch { if (opened) apply(request) }
    }

    // The whole meeting, lines included. null when it cannot be read.
    suspend fun meeting(id: String): Meeting? = withContext(calls) {
        try {
            core.meeting(id).text?.let { CoreJson.decodeFromString<Meeting>(it) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun apply(request: CoreRequest) {
        core.apply(CoreJson.encodeToString(CoreRequest.serializer(), request))
    }

    // Nothing here calls the core, which would wait for itself.
    private fun follow(event: String) {
        when (val read = readEvent(event)) {
            null -> Unit
            is CoreEvent.Listed -> list { read.meetings }
            is CoreEvent.Changed -> list { told ->
                told.filterNot { it.id == read.meeting.id }.toMutableList().apply {
                    // Bounded: after an event this app could not read, a place past the end must not throw into the
                    // core.
                    add(read.at.coerceIn(0, size), read.meeting)
                }
            }
            is CoreEvent.Removed -> list { told -> told.filterNot { it.id in read.meetings } }
            is CoreEvent.Unsaved -> behind.value = read.meetings
            // Followed once the app records through the core.
            is CoreEvent.Recorder -> Unit
        }
    }

    // Shown by compare-and-set as well, the list read inside it: whichever thread shows last shows the latest list.
    private fun list(change: (List<Meeting>) -> List<Meeting>) {
        told.update(change)
        if (listed) shown.update { told.value }
    }
}
