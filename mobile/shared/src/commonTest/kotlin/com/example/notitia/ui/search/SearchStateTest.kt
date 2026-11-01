package com.example.notitia.ui.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SearchStateTest {

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
