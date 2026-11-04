package com.example.notitia.ui.search

// Two steps, so the screen can count the sources while the answer is written.
interface AnswerEngine {
    suspend fun findSources(question: String): List<Passage>

    suspend fun writeAnswer(question: String, sources: List<Passage>): Answer?

    // An answer is not kept across a rotation: by default, the question is asked again.
    fun restoredStep(question: String): SearchStep = SearchStep.Asking(question, found = null)
}
