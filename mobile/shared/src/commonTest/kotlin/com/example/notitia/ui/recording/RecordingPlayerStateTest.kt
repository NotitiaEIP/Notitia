package com.example.notitia.ui.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordingPlayerStateTest {

    private fun collapsedPlayer() =
        RecordingPlayerState(PlayerPosition.Collapsed, openOnStart = false).apply { updateAnchors(1000f) }

    @Test
    fun progressFollowsTheFinger() {
        val player = collapsedPlayer()
        assertEquals(0f, player.progress)
        player.draggable.dispatchRawDelta(-250f)
        assertEquals(0.25f, player.progress)
        assertEquals(750f, player.visibleOffset)
        player.draggable.dispatchRawDelta(-750f)
        assertEquals(1f, player.progress)
    }

    @Test
    fun theSheetNeverLeavesItsTwoPositions() {
        val player = collapsedPlayer()
        player.draggable.dispatchRawDelta(-5000f)
        assertEquals(0f, player.visibleOffset)
        player.draggable.dispatchRawDelta(9000f)
        assertEquals(1000f, player.visibleOffset)
    }

    @Test
    fun aNewRecordingAsksToOpenAndARestoredOneDoesNot() {
        assertEquals(PlayerPosition.Expanded, RecordingPlayerState(PlayerPosition.Collapsed, openOnStart = true).request)
        assertNull(RecordingPlayerState(PlayerPosition.Expanded, openOnStart = false).request)
    }
}
