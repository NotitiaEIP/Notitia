package com.example.notitia.ui.search

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SearchStateTest {

    private val question = "Qui prépare la répétition ?"
    private val passage = samplePassages.first()
    private val answer = Answer(listOf(AnswerPart("Clément", source = 0)), listOf(passage), followUps = emptyList())

    private class FakeEngine(
        val sources: List<Passage> = emptyList(),
        val answer: Answer? = null,
        val failure: Exception? = null,
    ) : AnswerEngine {
        override suspend fun findSources(question: String): List<Passage> {
            failure?.let { throw it }
            return sources
        }

        override suspend fun writeAnswer(question: String, sources: List<Passage>): Answer? = answer
    }

    private fun answered(engine: AnswerEngine): SearchState {
        val state = SearchState(engine)
        state.submit(question)
        runNow { state.answer(question) }
        return state
    }

    @Test
    fun theEngineAnswerIsShown() {
        val state = answered(FakeEngine(listOf(passage), answer))
        assertEquals(SearchStep.Answered(question, answer), state.step)
    }

    @Test
    fun noSourceOrNoAnswerMeansNothingFound() {
        val nothingFound = SearchStep.NothingFound(question, QueryKind.Question)
        assertEquals(nothingFound, answered(FakeEngine()).step)
        assertEquals(nothingFound, answered(FakeEngine(listOf(passage), answer = null)).step)
    }

    @Test
    fun aFailingEngineShowsNothingFound() {
        val state = answered(FakeEngine(failure = IllegalStateException("HTTP 401")))
        assertEquals(SearchStep.NothingFound(question, QueryKind.Question), state.step)
    }

    @Test
    fun aCancelledQuestionLeavesTheStepToTheNextOne() {
        val state = SearchState(FakeEngine(failure = CancellationException("another question")))
        state.submit(question)
        assertFailsWith<CancellationException> { runNow { state.answer(question) } }
        assertEquals(SearchStep.Asking(question, found = null), state.step)
    }

    @Test
    fun aRestoredQuestionIsAskedAgainByAnEngineThatKeepsNothing() {
        val engine = FakeEngine(listOf(passage), answer)
        val restored = assertNotNull(SearchState.saver(engine).restore(question))
        assertEquals(SearchStep.Asking(question, found = null), restored.step)
    }

    @Test
    fun aRestoredQuestionFindsItsSampleAnswerWithoutTheLoader() {
        val restored = assertNotNull(SearchState.saver(SampleAnswerEngine).restore(question))
        assertIs<SearchStep.Answered>(restored.step)
        val unknown = assertNotNull(SearchState.saver(SampleAnswerEngine).restore("Quand a lieu la soutenance ?"))
        assertIs<SearchStep.NothingFound>(unknown.step)
    }

    @Test
    fun aRestoredWordSearchStaysLocal() {
        val engine = FakeEngine(failure = IllegalStateException("no call expected"))
        val restored = assertNotNull(SearchState.saver(engine).restore("budget"))
        assertIs<SearchStep.Words>(restored.step)
    }

    @Test
    fun anEmptyQueryLaunchesNothing() {
        val state = SearchState()
        state.submit("   ")
        assertEquals(SearchStep.Home, state.step)
    }

    @Test
    fun wordsShowAtOnceAndStayInTheField() {
        val state = SearchState()
        state.submit(" budget ")
        val step = assertIs<SearchStep.Words>(state.step)
        assertEquals(4, step.passages.size)
        assertEquals("budget", state.input.text.toString())
    }

    @Test
    fun aQuestionEmptiesTheFieldAndWaitsForTheEngine() {
        val state = SearchState()
        state.submit("Qui prépare la répétition ?")
        assertEquals(SearchStep.Asking("Qui prépare la répétition ?", found = null), state.step)
        assertEquals("", state.input.text.toString())
    }

    @Test
    fun resetGoesBackHomeWithAnEmptyField() {
        val state = SearchState()
        state.submit("budgte")
        assertEquals(SearchStep.NothingFound("budgte", QueryKind.Words), state.step)
        state.reset()
        assertEquals(SearchStep.Home, state.step)
        assertEquals("", state.input.text.toString())
    }
}
