package com.example.notitia.ui.search

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

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

// A quick trip to another app (to paste the answer) keeps the search; a real absence opens a blank one, as in
// ChatGPT and Claude. The search left stays in the history.
val NEW_SEARCH_AFTER = 10.minutes

// Kept by App, so the search shown survives a change of tab and an opened meeting.
@Stable
class SearchState(
    private val engine: AnswerEngine = SampleAnswerEngine,
    private val clock: Clock = Clock.System,
    history: List<HistoryEntry> = sampleHistory(clock.now()),
) {
    val input = TextFieldState()

    var step: SearchStep by mutableStateOf(SearchStep.Home)
        private set

    var history: List<HistoryEntry> by mutableStateOf(history)
        private set

    // True from the moment an answer arrives until the screen has played it: back on the screen, it is already there.
    var revealing by mutableStateOf(false)
        private set

    // Asks the screen to put the cursor in the field, from wherever the new search was started.
    var fieldRequested by mutableStateOf(false)
        private set

    // The answers of this session, so a question opened from the history shows again without the engine.
    private val answers = mutableMapOf<String, Answer>()
    private var cleared: List<HistoryEntry> = emptyList()
    private var deleted: IndexedValue<HistoryEntry>? = null
    private var leftAt: Instant? = null

    fun submit(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        val kind = queryKind(query)
        history = history.add(HistoryEntry(query, kind, clock.now()))
        if (kind == QueryKind.Words) return showResult(query)
        input.clearText()
        if ((step as? SearchStep.Asking)?.question != query) step = SearchStep.Asking(query, found = null)
    }

    // Shown at once, and left where it is in the history.
    fun open(entry: HistoryEntry) {
        revealing = false
        if (entry.kind == QueryKind.Words) return showResult(entry.text)
        input.clearText()
        val answer = answers[questionKey(entry.text)] ?: sampleAnswer(entry.text)
        step = answer?.let { SearchStep.Answered(entry.text, it) }
            ?: SearchStep.NothingFound(entry.text, QueryKind.Question)
    }

    fun reset() {
        input.clearText()
        revealing = false
        step = SearchStep.Home
    }

    fun startNewSearch() {
        reset()
        fieldRequested = true
    }

    fun fieldFocused() {
        fieldRequested = false
    }

    fun revealed() {
        revealing = false
    }

    // Pinned searches stay: pinning one says "keep it".
    fun clearHistory() {
        val (pinned, others) = history.partition { it.pinned }
        cleared = others
        history = pinned
    }

    // Behind the searches launched since, which are more recent.
    fun undoClear() {
        history = history + cleared.filterNot { old -> history.any { sameQuestion(it.text, old.text) } }
        cleared = emptyList()
    }

    fun pin(entry: HistoryEntry, pinned: Boolean) {
        history = history.pin(entry, pinned)
    }

    fun rename(entry: HistoryEntry, title: String) {
        history = history.rename(entry, title)
    }

    fun delete(entry: HistoryEntry) {
        val index = history.indexOfFirst { sameQuestion(it.text, entry.text) }
        if (index < 0) return
        deleted = IndexedValue(index, history[index])
        history = history.remove(entry)
    }

    // Back at its place, unless it was launched again since.
    fun undoDelete() {
        val (index, entry) = deleted ?: return
        deleted = null
        if (history.any { sameQuestion(it.text, entry.text) }) return
        history = history.toMutableList().apply { add(index.coerceAtMost(size), entry) }
    }

    fun leave() {
        leftAt = clock.now()
    }

    // True when the absence was long enough to start a blank search.
    fun comeBack(): Boolean {
        val away = leftAt?.let { clock.now() - it }
        leftAt = null
        if (away == null || away < NEW_SEARCH_AFTER) return false
        reset()
        return true
    }

    internal suspend fun answer(question: String) {
        val answer = try {
            val sources = engine.findSources(question)
            // Nothing found means nothing to write: the second phase is skipped.
            if (sources.isEmpty()) {
                null
            } else {
                step = SearchStep.Asking(question, found = sources.size)
                engine.writeAnswer(question, sources)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No screen says that the engine failed yet: it shows as nothing found.
            null
        }
        answer?.let { answers[questionKey(question)] = it }
        revealing = true
        step = answer?.let { SearchStep.Answered(question, it) }
            ?: SearchStep.NothingFound(question, QueryKind.Question)
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
            QueryKind.Question -> engine.restoredStep(query)
        }
    }

    companion object {
        private const val FIELDS_PER_ENTRY = 5

        // The query shown, when it was saved, then the history: text, kind, date, title and pin of each search. A
        // result is found again from its query, and only if the app was not left for too long.
        fun saver(engine: AnswerEngine, clock: Clock = Clock.System): Saver<SearchState, Any> = listSaver(
            save = { state ->
                listOf(state.step.searched.orEmpty(), clock.now().toEpochMilliseconds()) +
                    state.history.flatMap {
                        listOf(it.text, it.kind.name, it.at.toEpochMilliseconds(), it.title.orEmpty(), it.pinned)
                    }
            },
            restore = { saved ->
                val searched = saved[0] as String
                val savedAt = Instant.fromEpochMilliseconds(saved[1] as Long)
                val history = saved.drop(2).chunked(FIELDS_PER_ENTRY).map { (text, kind, at, title, pinned) ->
                    HistoryEntry(
                        text = text as String,
                        kind = QueryKind.valueOf(kind as String),
                        at = Instant.fromEpochMilliseconds(at as Long),
                        title = (title as String).ifEmpty { null },
                        pinned = pinned as Boolean,
                    )
                }
                SearchState(engine, clock, history).apply {
                    if (searched.isNotEmpty() && clock.now() - savedAt < NEW_SEARCH_AFTER) showResult(searched)
                }
            },
        )
    }
}
