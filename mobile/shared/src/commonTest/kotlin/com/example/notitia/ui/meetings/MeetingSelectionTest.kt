package com.example.notitia.ui.meetings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeetingSelectionTest {

    private val meetings = listOf("recorded-1", "recorded-2", "recorded-3")

    @Test
    fun selectFromACardStartsWithItsMeetingChosen() {
        val selection = MeetingSelection(closesWhenEmpty = true)
        selection.start("recorded-2")
        assertEquals(setOf("recorded-2"), selection.chosen)
    }

    @Test
    fun aTouchChoosesThenLetsGo() {
        val selection = MeetingSelection(closesWhenEmpty = false)
        selection.start()
        selection.toggle("recorded-1")
        selection.toggle("recorded-3")
        selection.toggle("recorded-1")
        assertEquals(setOf("recorded-3"), selection.chosen)
    }

    @Test
    fun onAndroidTheLastMeetingLetGoClosesTheSelection() {
        val selection = MeetingSelection(closesWhenEmpty = true)
        selection.start("recorded-1")
        selection.toggle("recorded-1")
        assertFalse(selection.active)
    }

    @Test
    fun onIphoneTheSelectionStaysOpenWithNothingChosen() {
        val selection = MeetingSelection(closesWhenEmpty = false)
        selection.start("recorded-1")
        selection.toggle("recorded-1")
        assertEquals(emptySet(), selection.chosen)
    }

    @Test
    fun chooseAllThenLetGoOfAll() {
        val selection = MeetingSelection(closesWhenEmpty = false)
        selection.start("recorded-1")
        selection.toggleAll(meetings)
        assertTrue(selection.allChosen(meetings))
        selection.toggleAll(meetings)
        assertEquals(emptySet(), selection.chosen)
    }

    @Test
    fun nothingIsChosenOutsideTheSelection() {
        val selection = MeetingSelection(closesWhenEmpty = true)
        selection.toggle("recorded-1")
        selection.toggleAll(meetings)
        assertNull(selection.chosen)
        assertFalse(selection.allChosen(meetings))
    }

    @Test
    fun endingForgetsTheChoice() {
        val selection = MeetingSelection(closesWhenEmpty = false)
        selection.start("recorded-1")
        selection.end()
        assertNull(selection.chosen)
    }

    @Test
    fun deletingAsksOnlyWithAMeetingChosen() {
        val selection = MeetingSelection(closesWhenEmpty = false)
        selection.start()
        selection.askToDelete(anchor = null)
        assertNull(selection.deleteRequest)
        selection.toggle("recorded-1")
        selection.askToDelete(anchor = null)
        assertEquals(DeleteRequest(setOf("recorded-1"), anchor = null), selection.deleteRequest)
        selection.end()
        assertNull(selection.deleteRequest)
    }

    @Test
    fun aCardsMenuAsksToDeleteItsMeetingWithoutChoosing() {
        val selection = MeetingSelection(closesWhenEmpty = true)
        selection.askToDelete("recorded-2", anchor = null)
        assertEquals(DeleteRequest(setOf("recorded-2"), anchor = null), selection.deleteRequest)
        assertFalse(selection.active)
    }
}
