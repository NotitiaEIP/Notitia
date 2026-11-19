package com.example.notitia.bridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.example.notitia.ai.MinutesWriter
import com.example.notitia.ai.SpeechEngine
import com.example.notitia.meeting.MeetingStatus
import com.example.notitia.meeting.MinutesDraft
import com.example.notitia.meeting.RecorderState
import com.example.notitia.meeting.Recording
import com.example.notitia.meeting.RecordingProblem
import com.example.notitia.recording.MeetingTranscription
import com.example.notitia.recording.Microphone
import com.example.notitia.recording.MicrophoneException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt

// A recording as the core holds it: the screens read the core's state, which this follows step by step. The
// microphone listens only while the core records, and its sound feeds the core and, until the core transcribes too
// (T-59), the transcription made here.
internal class CoreRecording(
    private val session: CoreSession,
    private val microphone: Microphone?,
    private val speech: SpeechEngine?,
    private val writer: MinutesWriter?,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    // One thing at a time: the steps of the recorder and what the transcription hands to the core never overlap, so
    // nothing here needs a lock. The microphone reads on its own thread.
    work: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    // Apart from that work: an engine slow to answer never keeps the microphone open after a stop.
    private val engines: CoroutineDispatcher = Dispatchers.Default,
) : Recording {
    private val scope = CoroutineScope(SupervisorJob() + work)

    // The meeting comes at the first sound, after the transcription has begun.
    private class Take(val transcription: MeetingTranscription) {
        var meeting: String? = null
    }

    // Touched only by follow, one state after the other.
    private var take: Take? = null
    private var listening: Job? = null
    private var previous: RecorderState = RecorderState.Idle

    // The meetings still transcribed, whose progress the kept meeting does not hold.
    private var transcribing by mutableStateOf(emptyMap<String, MeetingTranscription>())

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

    override fun progress(meetingId: String): MeetingStatus? = transcribing[meetingId]?.progress

    private suspend fun follow(now: RecorderState) {
        val was = previous
        previous = now
        when (now) {
            is RecorderState.Active -> {
                val take = take ?: begun(now)
                if (take.meeting == null) now.meetingId?.let { keep(take, it) }
                // Opened on entering the state, and only when no session lives: a pause keeps the microphone's,
                // which stops listening by itself, and the steps told after it died never open another.
                if (now is RecorderState.Recording && was !is RecorderState.Recording && listening?.isActive != true) {
                    listening = listen(take.transcription)
                }
            }
            RecorderState.Idle, is RecorderState.Failed -> ended()
        }
    }

    private fun begun(now: RecorderState.Active): Take {
        val transcription = MeetingTranscription(now.startedAt, speech, writer, timeZone())
        scope.launch(engines) { transcription.transcribeAll() }
        return Take(transcription).also { take = it }
    }

    // A pause, or the core interrupted by its disk: the microphone stops listening until the core records again. This
    // take, known by its start: one the core began before this app followed the stop of this one is not heard here.
    private fun listen(transcription: MeetingTranscription): Job = scope.launch {
        val problem = try {
            val microphone = microphone ?: throw MicrophoneException(RecordingProblem.Unavailable)
            val start = transcription.startedAt
            microphone.listen({ (session.recorder.value as? RecorderState.Recording)?.startedAt != start }) { pcm ->
                session.hear(pcm)
                transcription.addAudio(pcm)
            }
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

    // The microphone is let go first, so no sound reaches the transcription after its end. A recording that made no
    // meeting keeps nothing.
    private suspend fun ended() {
        listening?.cancelAndJoin()
        listening = null
        val ended = take ?: return
        take = null
        ended.transcription.finishListening()
    }

    // Its lines at each new one, and its minutes once written, until then shown as progress: the core keeps them
    // with the meeting it made, and never stays writing once the minutes are settled.
    private fun keep(take: Take, meeting: String) {
        take.meeting = meeting
        val transcription = take.transcription
        transcribing += meeting to transcription
        scope.launch {
            snapshotFlow { transcription.lines to transcription.minutes }
                .filter { (lines, minutes) -> lines.isNotEmpty() || minutes != MinutesDraft.Writing }
                .onEach { (lines, minutes) -> session.send(Change.Transcribed(meeting, lines, minutes)) }
                .first { (_, minutes) -> minutes != MinutesDraft.Writing }
            transcribing -= meeting
        }
    }
}
