package com.example.notitia.recording

import com.example.notitia.meeting.RecordingProblem
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred

// Stands in for the phone's microphone: the test says what is heard, and when the microphone is taken away. It may
// listen on another thread than the test's.
class FakeMicrophone(private val refusal: RecordingProblem? = null) : Microphone {
    @Volatile
    var listens = 0
        private set

    // From the start of a session to its end, whatever ended it.
    @Volatile
    var listening = false
        private set

    @Volatile
    private var isPaused: () -> Boolean = { true }

    @Volatile
    private var onAudio: ((ByteArray) -> Unit)? = null
    private var cut = CompletableDeferred<RecordingProblem>()

    val paused: Boolean get() = isPaused()

    // Like a real microphone, a paused one hears nothing.
    fun hear(pcm: ByteArray) {
        if (!isPaused()) onAudio?.invoke(pcm)
    }

    fun cutOff(problem: RecordingProblem) {
        cut.complete(problem)
    }

    override suspend fun listen(isPaused: () -> Boolean, onAudio: (ByteArray) -> Unit) {
        listens++
        refusal?.let { throw MicrophoneException(it) }
        this.isPaused = isPaused
        this.onAudio = onAudio
        cut = CompletableDeferred()
        listening = true
        try {
            throw MicrophoneException(cut.await())
        } finally {
            listening = false
            this.onAudio = null
        }
    }
}
