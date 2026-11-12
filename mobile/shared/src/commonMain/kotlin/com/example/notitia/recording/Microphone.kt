package com.example.notitia.recording

// The only part of a recording each platform writes: AudioRecorder keeps the state, the duration and the level.
interface Microphone {
    // Gives PCM until cancelled, and throws MicrophoneException when it cannot listen. isPaused releases the
    // microphone, so the system indicator goes out too: a paused meeting is not listened to.
    suspend fun listen(isPaused: () -> Boolean, onAudio: (ByteArray) -> Unit)
}

enum class MicrophoneProblem { Denied, Busy, Unavailable }

class MicrophoneException(val problem: MicrophoneProblem) : Exception(problem.name)
