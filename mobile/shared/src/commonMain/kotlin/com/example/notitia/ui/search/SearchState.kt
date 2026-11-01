package com.example.notitia.ui.search

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

sealed interface SearchStep {
    data object Home : SearchStep
    data class Words(val query: String, val passages: List<Passage>) : SearchStep
    // found is null while the meetings are searched, then counts the sources while the answer is written.
    data class Asking(val question: String, val found: Int?) : SearchStep
    data class Answered(val question: String, val answer: Answer) : SearchStep
    data class NothingFound(val query: String, val kind: QueryKind) : SearchStep
}

val SearchStep.searched: String?
    get() = when (this) {
        SearchStep.Home -> null
        is SearchStep.Words -> query
        is SearchStep.Asking -> question
        is SearchStep.Answered -> question
        is SearchStep.NothingFound -> query
    }

// Kept by App, so the search shown survives a change of tab and an opened meeting.
@Stable
class SearchState {
    val input = TextFieldState()

    var step: SearchStep by mutableStateOf(SearchStep.Home)
        private set

    fun submit(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        if (queryKind(query) == QueryKind.Words) return showResult(query)
        input.clearText()
        if ((step as? SearchStep.Asking)?.question != query) step = SearchStep.Asking(query, found = null)
    }

    fun reset() {
        input.clearText()
        step = SearchStep.Home
    }

    // Stands in for the engine (E6) while the screen shows the question.
    internal suspend fun answer(question: String) {
        delay(SAMPLE_SEARCH_TIME)
        val answer = sampleAnswer(question)
        if (answer == null) {
            // Nothing found means nothing to write: the second phase is skipped.
            step = SearchStep.NothingFound(question, QueryKind.Question)
            return
        }
        step = SearchStep.Asking(question, found = answer.sources.size)
        delay(SAMPLE_WRITING_TIME)
        step = SearchStep.Answered(question, answer)
    }

    private fun showResult(query: String) {
        step = when (queryKind(query)) {
            QueryKind.Words -> {
                input.setTextAndPlaceCursorAtEnd(query)
                findPassages(query, samplePassages)
                    .takeIf { it.isNotEmpty() }
                    ?.let { SearchStep.Words(query, it) }
                    ?: SearchStep.NothingFound(query, QueryKind.Words)
            }
            QueryKind.Question ->
                sampleAnswer(query)?.let { SearchStep.Answered(query, it) }
                    ?: SearchStep.NothingFound(query, QueryKind.Question)
        }
    }

    companion object {
        // Only the query is kept: its result is found again in the samples, without the loader.
        val Saver: Saver<SearchState, String> = Saver(
            save = { it.step.searched },
            restore = { query -> SearchState().apply { showResult(query) } },
        )
    }
}
