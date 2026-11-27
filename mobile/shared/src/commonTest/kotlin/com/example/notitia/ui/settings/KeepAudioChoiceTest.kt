package com.example.notitia.ui.settings

import androidx.compose.ui.geometry.Rect
import com.example.notitia.meeting.AudioKeeping
import com.example.notitia.meeting.KeepAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class KeepAudioChoiceTest {

    // A core that has not answered yet: what is sent never moves the choice shown.
    private class Keeping(now: KeepAudio?) : AudioKeeping {
        val sent = mutableListOf<KeepAudio>()
        override val keepAudio = MutableStateFlow(now)

        override fun keep(choice: KeepAudio) {
            sent += choice
        }
    }

    private val row = Rect(0f, 120f, 400f, 172f)

    @Test
    fun aLongerChoiceIsSentAtOnceWithoutAsking() {
        val keeping = Keeping(KeepAudio.Day)
        val choice = KeepAudioChoice(keeping)
        choice.choose(KeepAudio.Week, row)
        assertNull(choice.asked)
        assertEquals(listOf(KeepAudio.Week), keeping.sent)
    }

    @Test
    fun aShorterChoiceWaitsForItsConfirmationAndSendsNothing() {
        val keeping = Keeping(KeepAudio.Month)
        val choice = KeepAudioChoice(keeping)
        choice.choose(KeepAudio.Day, row)
        assertEquals(KeepAudio.Day, choice.asked?.choice)
        assertTrue(keeping.sent.isEmpty())
        assertEquals(KeepAudio.Month, keeping.keepAudio.value)
    }

    @Test
    fun aConfirmedChoiceIsSentOnceAndTheQuestionCloses() {
        val keeping = Keeping(KeepAudio.Month)
        val choice = KeepAudioChoice(keeping)
        choice.choose(KeepAudio.UntilHeard, row)
        choice.confirm()
        assertNull(choice.asked)
        assertEquals(listOf(KeepAudio.UntilHeard), keeping.sent)
    }

    @Test
    fun aCancelledChoiceSendsNothing() {
        val keeping = Keeping(KeepAudio.Week)
        val choice = KeepAudioChoice(keeping)
        choice.choose(KeepAudio.Day, row)
        choice.cancel()
        assertNull(choice.asked)
        assertTrue(keeping.sent.isEmpty())
    }

    @Test
    fun theSameChoiceAsksNothing() {
        val choice = KeepAudioChoice(Keeping(KeepAudio.Week))
        choice.choose(KeepAudio.Week, row)
        assertNull(choice.asked)
    }

    @Test
    fun nothingHappensBeforeTheCoreHasSaid() {
        val keeping = Keeping(null)
        val choice = KeepAudioChoice(keeping)
        choice.choose(KeepAudio.UntilHeard, row)
        choice.choose(KeepAudio.Month, row)
        assertNull(choice.asked)
        assertTrue(keeping.sent.isEmpty())
    }

    @Test
    fun aTouchWhileAskingIsIgnored() {
        val keeping = Keeping(KeepAudio.Month)
        val choice = KeepAudioChoice(keeping)
        choice.choose(KeepAudio.Week, row)
        choice.choose(KeepAudio.Day, null)
        choice.choose(KeepAudio.Month, null)
        assertEquals(AskedChoice(KeepAudio.Week, row), choice.asked)
        assertTrue(keeping.sent.isEmpty())
    }

    @Test
    fun confirmingWithNothingAskedSendsNothing() {
        val keeping = Keeping(KeepAudio.Month)
        KeepAudioChoice(keeping).confirm()
        assertTrue(keeping.sent.isEmpty())
    }

    @Test
    fun theAskedChoiceKeepsItsAnchor() {
        val choice = KeepAudioChoice(Keeping(KeepAudio.Month))
        choice.choose(KeepAudio.Week, row)
        assertEquals(row, choice.asked?.anchor)
    }

    // What makes a choice shorter: the order the core names them in.
    @Test
    fun theChoicesGoFromTheShortestToTheLongest() {
        assertEquals(listOf(KeepAudio.UntilHeard, KeepAudio.Day, KeepAudio.Week, KeepAudio.Month), KeepAudio.entries)
    }
}
