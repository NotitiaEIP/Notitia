package com.example.notitia.recording

import com.example.notitia.meeting.RecorderState
import com.example.notitia.meeting.RecordingProblem
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The one truth about a recording: the screen, and later the recording service, only read it. microphone is null
// on a platform that cannot record yet.
class AudioRecorder(private val microphone: Microphone?, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = mutableState.asStateFlow()

    // Read by the microphone on its own thread.
    @Volatile
    private var paused = false
    private var listening: Job? = null
    private var onAudio: (ByteArray) -> Unit = {}

    // onAudio gets each read on the microphone's thread. The timer only moves with heard audio, so a microphone
    // that never opens leaves it at zero until it fails. False when a recording already runs.
    fun start(startedAt: Instant, onAudio: (ByteArray) -> Unit): Boolean {
        if (state.value is RecorderState.Active) return false
        this.onAudio = onAudio
        mutableState.value = RecorderState.Recording(startedAt, Duration.ZERO, level = 0f)
        listen()
        return true
    }

    fun togglePause() {
        when (val current = state.value) {
            is RecorderState.Recording -> {
                paused = true
                mutableState.value = RecorderState.Paused(current.startedAt, current.recorded)
            }
            is RecorderState.Paused -> {
                paused = false
                mutableState.value = RecorderState.Recording(current.startedAt, current.recorded, level = 0f)
            }
            is RecorderState.Interrupted -> {
                mutableState.value = RecorderState.Recording(current.startedAt, current.recorded, level = 0f)
                listen()
            }
            RecorderState.Idle, is RecorderState.Failed -> Unit
        }
    }

    // Returns the audio heard, the last read included, or null when nothing was being recorded.
    suspend fun stop(): Duration? {
        if (state.value !is RecorderState.Active) return null
        listening?.cancelAndJoin()
        listening = null
        val recorded = (state.value as? RecorderState.Active)?.recorded
        mutableState.value = RecorderState.Idle
        return recorded
    }

    fun dismissFailure() {
        mutableState.update { if (it is RecorderState.Failed) RecorderState.Idle else it }
    }

    private fun listen() {
        paused = false
        val microphone = microphone ?: return cut(RecordingProblem.Unavailable)
        listening = scope.launch {
            val problem = try {
                microphone.listen({ paused }, ::heard)
                RecordingProblem.Unavailable
            } catch (e: MicrophoneException) {
                e.problem
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A microphone failing in a way nobody foresaw shows an alert, it does not crash a meeting.
                RecordingProblem.Unavailable
            }
            ensureActive()
            cut(problem)
        }
    }

    private fun heard(pcm: ByteArray) {
        onAudio(pcm)
        val length = pcmDuration(pcm.size)
        mutableState.update {
            when (it) {
                is RecorderState.Recording -> it.copy(recorded = it.recorded + length, level = loudness(pcm))
                // A read already under way when the pause came: the audio is real, it counts.
                is RecorderState.Paused -> it.copy(recorded = it.recorded + length)
                else -> it
            }
        }
    }

    private fun cut(problem: RecordingProblem) = mutableState.update {
        if (it is RecorderState.Active && it.recorded > Duration.ZERO) {
            RecorderState.Interrupted(it.startedAt, it.recorded)
        } else {
            RecorderState.Failed(problem)
        }
    }
}
