package com.example.notitia.recording

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.example.notitia.ai.SpeechEngine
import com.example.notitia.meeting.MinutesState
import com.example.notitia.storage.MeetingRepository
import com.example.notitia.ui.meetings.MeetingStatus
import com.example.notitia.ui.minutes.MinutesWriter
import com.example.notitia.ui.recording.MeetingTranscription
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

// A meeting on disk at most this far behind what was heard, if the app is killed while it records.
private const val SAVE_EVERY_SECONDS = 10

// Hears a meeting, transcribes it as it goes and keeps it. One per process, held by Notitia: the screen and the
// recording service read the same state.
class MeetingRecorder(
    microphone: Microphone?,
    private val speech: SpeechEngine?,
    private val writer: MinutesWriter?,
    private val repository: MeetingRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private val recorder = AudioRecorder(microphone, scope)
    val state: StateFlow<RecorderState> = recorder.state

    // The meetings still being transcribed, whose progress the kept meeting does not hold.
    private var transcribing by mutableStateOf(emptyMap<String, MeetingTranscription>())

    // Also cleared by a start that failed, off the main thread.
    @Volatile
    private var current: MeetingTranscription? = null

    fun progress(id: String): MeetingStatus? = transcribing[id]?.status

    fun start() {
        val transcription = MeetingTranscription(clock.now(), speech, writer)
        if (!recorder.start(transcription.startedAt, transcription::addAudio)) return
        current = transcription
        scope.launch { transcription.transcribeAll() }
        scope.launch {
            // Kept once the microphone is heard: a refused one leaves no empty meeting behind.
            val began = state.first { it !is RecorderState.Recording || it.recorded > Duration.ZERO }
            if (began is RecorderState.Active) {
                keep(transcription)
            } else {
                if (current === transcription) current = null
                transcribing -= transcription.id
                transcription.finishListening()
            }
        }
    }

    fun togglePause() = recorder.togglePause()

    fun dismissFailure() = recorder.dismissFailure()

    fun stop() {
        val transcription = current ?: return
        current = null
        transcribing += transcription.id to transcription
        scope.launch {
            recorder.stop()?.let { transcription.duration = it }
            transcription.finishListening()
        }
    }

    // Written at each new line, at the end, and every few seconds of audio: an app killed mid-meeting keeps what
    // was heard and transcribed.
    private fun keep(transcription: MeetingTranscription) {
        scope.launch {
            state.takeWhile { it is RecorderState.Active }
                .collect { transcription.duration = (it as RecorderState.Active).recorded }
        }
        scope.launch {
            snapshotFlow {
                Triple(transcription.lines, transcription.minutes, transcription.duration.inWholeSeconds / SAVE_EVERY_SECONDS)
            }
                .onEach { repository.save(transcription.toMeeting(repository.device, clock.now())) }
                .first { (_, minutes) -> minutes != MinutesState.Writing }
            transcribing -= transcription.id
        }
    }
}
