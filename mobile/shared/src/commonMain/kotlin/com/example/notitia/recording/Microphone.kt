package com.example.notitia.recording

import com.example.notitia.meeting.RecordingProblem

// The only part of a recording each platform writes: the core keeps the state, the audio, the duration and the
// level.
interface Microphone {
    // Gives PCM until cancelled, and throws MicrophoneException when it cannot listen. isPaused releases the
    // microphone, so the system indicator goes out too: a paused meeting is not listened to.
    suspend fun listen(isPaused: () -> Boolean, onAudio: (ByteArray) -> Unit)
}

class MicrophoneException(val problem: RecordingProblem) : Exception(problem.name)
