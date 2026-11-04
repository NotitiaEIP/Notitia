package com.example.notitia.ai

interface LanguageModel {
    // One vector per text, in the same order.
    suspend fun embed(texts: List<String>): List<List<Double>>

    suspend fun complete(system: String, prompt: String): String
}
