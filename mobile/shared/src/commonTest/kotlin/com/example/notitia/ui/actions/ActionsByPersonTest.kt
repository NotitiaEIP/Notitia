package com.example.notitia.ui.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// Who has what, and in which order, is the core's rule, tested in Rust (core/src/actions.rs). What stays here is the
// order the cards keep on screen, the spelling a typed name takes, and the key of the colours.
class ActionsByPersonTest {

    private fun person(key: String?, name: String? = key) =
        PersonActions(key, name, open = emptyList(), late = 0, done = 0, total = 0)

    @Test
    fun firstNamesMatchWhateverTheirCaseAccentsOrSpaces() {
        assertEquals("clement", personKey("  Clément "))
        assertEquals(personKey("clement"), personKey("CLÉMENT"))
        assertEquals("anne marie", personKey("Anne   Marie"))
        assertEquals("zoe", personKey("Zoé"))
    }

    // Two people with the same first name are told apart by what the user wrote, such as an initial.
    @Test
    fun aFirstNameWithAnInitialIsSomeoneElse() {
        assertNotEquals(personKey("Clément B."), personKey("Clément D."))
    }

    @Test
    fun cardsKeepTheirPlaceWhileTheListIsOnScreen() {
        val before = listOf(person("nathan"), person("adam"), person(null))
        // Nathan's late action ticked, Zoé late since: the core now puts Zoé first and Adam before Nathan.
        val after = listOf(person("zoe"), person("adam"), person("nathan"), person(null))
        assertEquals(listOf("nathan", "adam", "zoe", null), after.keptInOrder(before.map { it.key }).map { it.key })
    }

    @Test
    fun aTypedFirstNameTakesTheSpellingTheMeetingKnows() {
        val known = listOf("Clément", "Nathan")
        assertEquals("Clément", knownSpelling("clement", known))
        assertEquals("Nathan", knownSpelling(" NATHAN ", known))
        assertEquals("Zoé", knownSpelling("Zoé", known))
    }
}
