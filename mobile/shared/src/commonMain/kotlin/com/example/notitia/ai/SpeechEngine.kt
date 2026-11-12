package com.example.notitia.ai

interface SpeechEngine {
    // previousText keeps the model on track from one piece to the next.
    suspend fun transcribe(wav: ByteArray, previousText: String): String
}
