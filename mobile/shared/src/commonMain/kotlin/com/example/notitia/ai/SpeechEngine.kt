package com.example.notitia.ai

interface SpeechEngine {
    // False when the user refused the microphone: the recording then stays made up.
    fun canListen(): Boolean

    // Gives 16 kHz mono 16-bit audio until cancelled. isPaused turns the microphone off, it does not just drop audio.
    suspend fun listen(isPaused: () -> Boolean, onAudio: (ByteArray) -> Unit)

    // previousText keeps the model on track from one piece to the next.
    suspend fun transcribe(wav: ByteArray, previousText: String): String
}
