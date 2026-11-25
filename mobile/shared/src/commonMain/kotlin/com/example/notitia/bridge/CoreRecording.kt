package com.example.notitia.bridge

import com.example.notitia.meeting.RecorderState
import com.example.notitia.meeting.Recording
import com.example.notitia.meeting.RecordingProblem
import com.example.notitia.recording.Microphone
import com.example.notitia.recording.MicrophoneException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt

// A recording as the core holds it: the screens read the core's state, which this follows step by step. The
// microphone listens only while the core records, and its sound goes to the core alone, which hears the meeting.
internal class CoreRecording(
    private val session: CoreSession,
    private val microphone: Microphone?,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    // One step at a time, so nothing here needs a lock. The microphone reads on its own thread.
    work: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : Recording {
    private val scope = CoroutineScope(SupervisorJob() + work)

    // Touched only by follow, one state after the other.
    private var listening: Job? = null
    private var previous: RecorderState = RecorderState.Idle

    override val state: StateFlow<RecorderState> = session.recorder

    init {
        // Following before any start, so not a step is missed.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { session.recorderChanges.collect { follow(it) } }
    }

    override fun start() {
        val offset = timeZone().offsetAt(clock.now()).totalSeconds / 60
        session.send(Command.Start(tracks = 1, offsetMinutes = offset))
    }

    override fun togglePause() = when (state.value) {
        is RecorderState.Recording -> session.send(Command.Pause)
        is RecorderState.Paused, is RecorderState.Interrupted -> session.send(Command.Resume)
        RecorderState.Idle, is RecorderState.Failed -> Unit
    }

    override fun stop() = session.send(Command.Stop)

    override fun dismissFailure() = session.send(Command.Dismiss)

    // Opened on entering the state, and only when no session lives: a pause keeps the microphone's, which stops
    // listening by itself, and the steps told after it died never open another. Let go once the take ends.
    private suspend fun follow(now: RecorderState) {
        val was = previous
        previous = now
        when (now) {
            is RecorderState.Recording -> if (was !is RecorderState.Recording && listening?.isActive != true) {
                listening = listen(now.startedAt)
            }
            is RecorderState.Paused, is RecorderState.Interrupted -> Unit
            RecorderState.Idle, is RecorderState.Failed -> {
                listening?.cancelAndJoin()
                listening = null
            }
        }
    }

    // A pause, or the core interrupted by its disk: the microphone stops listening until the core records again. This
    // take, known by its start: one the core began before this app followed the stop of this one is not heard here.
    private fun listen(start: Instant): Job = scope.launch {
        val problem = try {
            val microphone = microphone ?: throw MicrophoneException(RecordingProblem.Unavailable)
            microphone.listen({ (session.recorder.value as? RecorderState.Recording)?.startedAt != start }, session::hear)
            RecordingProblem.Unavailable
        } catch (e: MicrophoneException) {
            e.problem
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A microphone failing in a way nobody foresaw shows an alert, it does not crash a meeting.
            RecordingProblem.Unavailable
        }
        // Stopped by the recording, not by the microphone: nothing to tell.
        ensureActive()
        session.send(Command.Cut(problem))
    }
}
