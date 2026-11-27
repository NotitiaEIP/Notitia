package com.example.notitia.bridge

import com.example.notitia.ai.Engine
import com.example.notitia.meeting.Abilities
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.RecorderState
import com.example.notitia.meeting.RecordingProblem
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// An engine that said it was not available is offered again after this: the core waits for the next offer, and has
// no clock (ADR 0009). Out of reach, a gateway fails at once, so this costs two calls a minute.
private val RETRY_AFTER = 30.seconds

// The core erases an audio past its days only when asked, never on its own (ADR 0015): asked once it is open, then each
// day the app lives.
private val TIDY_EVERY = 24.hours

// The core as this app holds it: opened once, its calls made one at a time and in order, its events read once into
// the state they tell, and the engines of this app lent to it. The screens reach it through facades that keep no state
// of their own, CoreMeetings and CoreRecording: a new kind of event changes only the facade that follows it. Nothing is
// changed here, where no rule lives: a tick shows at once because the core tells it before it writes the file.
internal class CoreSession(
    private val core: NotitiaCore,
    directory: String,
    // The key of the app, which only the platform can keep. Read once, off the main thread, and wiped once the core
    // holds it. null: the meetings open, but nothing is recorded.
    key: () -> ByteArray? = { null },
    // Shown and changed like the others, never written: they are not the user's meetings.
    samples: List<Meeting> = emptyList(),
    // The engines this app can run, offered to the core once it is open, then again at each emission: a model that
    // has come, say.
    engines: Flow<List<Engine>> = emptyFlow(),
    // One call at a time and in order, off the main thread: the core writes the file before it returns. Not tied to
    // a screen: closing the app must not cancel a write.
    private val calls: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    // Before engines that said they were not available are offered again.
    private val beforeRetry: suspend () -> Unit = { delay(RETRY_AFTER) },
    // Between two tidy-ups of the audio.
    private val beforeTidy: suspend () -> Unit = { delay(TIDY_EVERY) },
) {
    private val scope = CoroutineScope(SupervisorJob() + calls)
    private val shown = MutableStateFlow<List<Meeting>?>(null)
    private val behind = MutableStateFlow(emptySet<String>())

    // The core tells the meetings one event at a time, from the thread of a call or from the recorder's: the list
    // changes by compare-and-set, so no change is lost between two threads.
    private val told = MutableStateFlow(emptyList<Meeting>())
    private val recording = MutableStateFlow<RecorderState>(RecorderState.Idle)
    private val able = MutableStateFlow(Abilities.None)

    // The engines offered last, touched by the calls alone, one at a time.
    private var lent: List<Engine> = emptyList()

    // Set from a thread of the core, by an engine that was not available: one retry waits at a time.
    private val retrying = MutableStateFlow(false)

    // Every state the recorder tells, in order: whoever drives the microphone acts on each step, which a StateFlow
    // could merge. Dropped while nobody follows.
    private val recorderSteps = MutableSharedFlow<RecorderState>(extraBufferCapacity = Int.MAX_VALUE)

    @Volatile
    private var opened = false

    @Volatile
    private var listed = false

    // null until the files are read, so the list never flashes empty at launch. Each meeting comes without its lines.
    val meetings: StateFlow<List<Meeting>?> = shown.asStateFlow()

    // The meetings whose file is behind what the screens show, after a write that failed.
    val unsaved: StateFlow<Set<String>> = behind.asStateFlow()

    val recorder: StateFlow<RecorderState> = recording.asStateFlow()

    val recorderChanges: Flow<RecorderState> = recorderSteps.asSharedFlow()

    val abilities: StateFlow<Abilities> = able.asStateFlow()

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
            if (opened) {
                scope.launch { tidyEveryDay() }
                engines.collect(::lend)
            }
        }
    }

    // After every call asked before it. A core that could not open answers the recorder itself, so a button never
    // goes unanswered; anything else has nothing to change.
    fun send(request: CoreRequest) {
        scope.launch {
            when {
                opened -> apply(request)
                request is Command.Start -> tellRecorder(RecorderState.Failed(RecordingProblem.Unavailable))
                request is Command.Dismiss -> tellRecorder(RecorderState.Idle)
            }
        }
    }

    // From the microphone's thread, straight to the core: never behind a call that waits for the disk.
    fun hear(pcm: ByteArray) {
        if (opened) core.hear(0, pcm)
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

    // After the list is shown, the first tidy-up of a phone may take seconds.
    private suspend fun tidyEveryDay() {
        while (scope.isActive) {
            apply(Change.Tidy)
            beforeTidy()
        }
    }

    // In place of those offered before.
    private fun lend(engines: List<Engine>) {
        lent = engines
        val lending = Lending(engines, ::retryLater)
        core.offer(lending.offer, lending)
    }

    // The core asks an engine away nothing more until the next offer: each kind of work says so once, and one retry
    // is enough for all.
    private fun retryLater() {
        if (!retrying.compareAndSet(expect = false, update = true)) return
        scope.launch {
            beforeRetry()
            retrying.value = false
            lend(lent)
        }
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
            is CoreEvent.Recorder -> read.toState()?.let(::tellRecorder)
            is CoreEvent.Engines -> able.value = Abilities(hear = read.speech.isNotEmpty(), write = read.text.isNotEmpty())
        }
    }

    // Shown by compare-and-set as well, the list read inside it: whichever thread shows last shows the latest list.
    private fun list(change: (List<Meeting>) -> List<Meeting>) {
        told.update(change)
        if (listed) shown.update { told.value }
    }

    private fun tellRecorder(state: RecorderState) {
        recording.value = state
        recorderSteps.tryEmit(state)
    }
}
