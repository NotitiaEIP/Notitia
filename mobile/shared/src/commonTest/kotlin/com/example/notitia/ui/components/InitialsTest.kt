package com.example.notitia.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class InitialsTest {

    @Test
    fun oneNameGivesOneLetter() {
        assertEquals("C", initials("Clément"))
    }

    @Test
    fun twoNamesGiveTwoLetters() {
        assertEquals("MC", initials("Marie Curie"))
        assertEquals("JP", initials("Jean-Pierre Martin"))
    }

    @Test
    fun extraSpacesAndLowercaseAreIgnored() {
        assertEquals("ÉL", initials("  élise   leroy "))
        assertEquals("", initials("   "))
    }
}
