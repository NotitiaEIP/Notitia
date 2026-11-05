package com.example.notitia.ui.search

import androidx.compose.runtime.saveable.SaverScope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

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

    private class TestClock(var now: Instant = Instant.fromEpochSeconds(1_793_000_000)) : Clock {
        override fun now(): Instant = now
    }

    private fun roundTrip(state: SearchState, engine: AnswerEngine, clock: Clock = Clock.System): SearchState {
        val saver = SearchState.saver(engine, clock)
        val saved = assertNotNull(with(saver) { SaverScope { true }.save(state) })
        return assertNotNull(saver.restore(saved))
    }

    private fun searched(engine: AnswerEngine, text: String) = SearchState(engine).apply { submit(text) }

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
        val restored = roundTrip(searched(engine, question), engine)
        assertEquals(SearchStep.Asking(question, found = null), restored.step)
    }

    @Test
    fun aRestoredQuestionFindsItsSampleAnswerWithoutTheLoader() {
        val engine = SampleAnswerEngine
        assertIs<SearchStep.Answered>(roundTrip(searched(engine, question), engine).step)
        val unknown = roundTrip(searched(engine, "Quand a lieu la soutenance ?"), engine)
        assertIs<SearchStep.NothingFound>(unknown.step)
    }

    @Test
    fun aRestoredWordSearchStaysLocal() {
        val engine = FakeEngine(failure = IllegalStateException("no call expected"))
        assertIs<SearchStep.Words>(roundTrip(searched(engine, "budget"), engine).step)
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

    @Test
    fun eachLaunchedSearchGoesToTheTopOfTheHistoryOnce() {
        val state = SearchState(history = emptyList())
        state.submit("budget")
        state.submit(question)
        state.submit(" Budget ")
        assertEquals(listOf("Budget", question), state.history.map { it.text })
        assertEquals(listOf(QueryKind.Words, QueryKind.Question), state.history.map { it.kind })
    }

    @Test
    fun anOpenedEntryShowsAtOnceWithoutTheEngineAndKeepsItsPlace() {
        val state = SearchState(FakeEngine(failure = IllegalStateException("no call expected")))
        val before = state.history
        fun open(text: String) = state.open(before.single { it.text == text })

        open(question)
        assertIs<SearchStep.Answered>(state.step)
        open("Quand a lieu la soutenance ?")
        assertEquals(SearchStep.NothingFound("Quand a lieu la soutenance ?", QueryKind.Question), state.step)
        open("budget")
        assertIs<SearchStep.Words>(state.step)
        assertEquals("budget", state.input.text.toString())
        assertEquals(before, state.history)
    }

    @Test
    fun aQuestionAnsweredInTheSessionOpensWithItsAnswer() {
        val asked = "Quelle est la date du lancement ?"
        val state = SearchState(FakeEngine(listOf(passage), answer))
        state.submit(asked)
        runNow { state.answer(asked) }
        state.reset()
        state.open(state.history.first())
        assertEquals(SearchStep.Answered(asked, answer), state.step)
    }

    @Test
    fun aClearedHistoryComesBackBehindTheNewSearches() {
        val state = SearchState()
        val samples = state.history
        state.clearHistory()
        assertEquals(emptyList(), state.history)
        state.submit("registre rgpd")
        state.undoClear()
        assertEquals(listOf("registre rgpd") + samples.map { it.text }.filterNot { it == "registre RGPD" }, state.history.map { it.text })
    }

    @Test
    fun aShortTripOutKeepsTheSearchAndALongOneStartsABlankOne() {
        val clock = TestClock()
        val state = SearchState(clock = clock)
        assertFalse(state.comeBack())
        state.submit("budget")

        state.leave()
        clock.now += 9.minutes
        assertFalse(state.comeBack())
        assertIs<SearchStep.Words>(state.step)

        state.leave()
        clock.now += NEW_SEARCH_AFTER
        assertTrue(state.comeBack())
        assertEquals(SearchStep.Home, state.step)
        assertEquals("budget", state.history.first().text)
    }

    @Test
    fun theHistoryIsSavedWithItsKindsAndDates() {
        val clock = TestClock()
        val state = SearchState(clock = clock, history = emptyList())
        state.submit("budget")
        clock.now += 1.minutes
        state.submit(question)
        val restored = roundTrip(state, SampleAnswerEngine, clock)
        assertEquals(state.history, restored.history)
    }

    @Test
    fun aSearchSavedLongAgoComesBackBlankWithItsHistory() {
        val clock = TestClock()
        val state = SearchState(clock = clock)
        state.submit("budget")
        val saver = SearchState.saver(SampleAnswerEngine, clock)
        val saved = assertNotNull(with(saver) { SaverScope { true }.save(state) })

        clock.now += 1.minutes
        assertIs<SearchStep.Words>(assertNotNull(saver.restore(saved)).step)
        clock.now += NEW_SEARCH_AFTER
        val restored = assertNotNull(saver.restore(saved))
        assertEquals(SearchStep.Home, restored.step)
        assertEquals(state.history, restored.history)
    }

    @Test
    fun anArrivingAnswerPlaysOnceAndAnOpenedOneDoesNot() {
        val state = answered(FakeEngine(listOf(passage), answer))
        assertTrue(state.revealing)
        state.revealed()
        assertFalse(state.revealing)

        val again = answered(FakeEngine(listOf(passage), answer))
        again.open(again.history.first())
        assertFalse(again.revealing)
    }

    @Test
    fun aNewSearchGoesHomeAndAsksForTheField() {
        val state = SearchState()
        state.submit("budget")
        state.startNewSearch()
        assertEquals(SearchStep.Home, state.step)
        assertEquals("", state.input.text.toString())
        assertTrue(state.fieldRequested)
        state.fieldFocused()
        assertFalse(state.fieldRequested)
    }
}
