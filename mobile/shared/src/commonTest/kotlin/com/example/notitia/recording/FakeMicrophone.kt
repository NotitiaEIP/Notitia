package com.example.notitia.recording

import kotlinx.coroutines.CompletableDeferred

// Stands in for the phone's microphone: the test says what is heard, and when the microphone is taken away.
class FakeMicrophone(private val refusal: MicrophoneProblem? = null) : Microphone {
    var listens = 0
        private set
    private var isPaused: () -> Boolean = { true }
    private var onAudio: ((ByteArray) -> Unit)? = null
    private var cut = CompletableDeferred<MicrophoneProblem>()

    val paused: Boolean get() = isPaused()

    // Like a real microphone, a paused one hears nothing.
    fun hear(pcm: ByteArray) {
        if (!isPaused()) onAudio?.invoke(pcm)
    }

    fun cutOff(problem: MicrophoneProblem) {
        cut.complete(problem)
    }

    override suspend fun listen(isPaused: () -> Boolean, onAudio: (ByteArray) -> Unit) {
        listens++
        refusal?.let { throw MicrophoneException(it) }
        this.isPaused = isPaused
        this.onAudio = onAudio
        cut = CompletableDeferred()
        try {
            throw MicrophoneException(cut.await())
        } finally {
            this.onAudio = null
        }
    }
}
