package com.example.notitia.bridge

import com.example.notitia.meeting.ActionBoard
import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.DueGroup
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.Placed
import com.example.notitia.ui.meetings.sampleMeetings
import com.example.notitia.ui.minutes.sampleBoard
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

// The board of the real core through the bridge of the phone, with the samples the app lends it, each call on the
// threads it has in the app. What the fake core cannot prove: that the places the screens show are the core's.
class RustBoardTest {
    private val folder = createTempDirectory("rust-board").toFile()
    private val paris = TimeZone.of("Europe/Paris")

    @AfterTest
    fun clear() {
        folder.deleteRecursively()
    }

    // A folder of its own for each session: a core keeps the one it opened.
    private fun opened(now: Instant, place: String = "meetings") =
        CoreMeetings(CoreSession(RustCore(), File(folder, place).path, samples = sampleMeetings(now, paris)))

    private suspend fun CoreMeetings.next(shows: (ActionBoard) -> Boolean): ActionBoard =
        withTimeout(10.seconds) { board.filterNotNull().first(shows) }

    private fun ActionBoard.action(id: ActionId): MinutesAction? = meetings.firstOrNull { it.id == id.meetingId }
        ?.let { (it.minutes as? MinutesState.Written)?.minutes?.actions }
        ?.firstOrNull { it.id == id.actionId }

    private fun ActionBoard.placed(group: DueGroup) = groups.firstOrNull { it.group == group }?.actions.orEmpty()

    // Five minutes after midnight and five before: the samples fall in the same places, those the previews show.
    @Test
    fun theCorePlacesTheSamplesAsThePreviewsShowThem() = runBlocking {
        listOf(LocalDateTime(2026, 10, 26, 0, 5), LocalDateTime(2026, 10, 26, 23, 55)).forEachIndexed { index, at ->
            val now = at.toInstant(paris)
            val expected = sampleBoard(now, paris)
            val kept = opened(now, place = "at-$index")
            kept.showBoard(expected.today)
            val board = kept.next { true }
            assertEquals(expected.copy(meetings = emptyList()), board.copy(meetings = emptyList()), "$at")
            assertEquals(sampleMeetings(now, paris).map { it.id }.toSet(), board.meetings.map { it.id }.toSet())
        }
    }

    // Ticked, it stays in its group on every board, then joins the done ones once let go.
    @Test
    fun aTickedActionKeepsItsPlaceUntilLetGo() = runBlocking {
        val now = LocalDateTime(2026, 10, 26, 10, 0).toInstant(paris)
        val kept = opened(now)
        val dueToday = ActionId("sample-4", "2")
        kept.showBoard(sampleBoard(now, paris).today)
        kept.next { true }
        kept.tick(dueToday, done = true)
        val lingering = kept.next { it.action(dueToday)?.done == true }
        assertEquals(listOf(Placed("sample-4", "2", DueGroup.Today)), lingering.placed(DueGroup.Today))
        assertEquals(9, lingering.open + lingering.done.size)
        kept.letGo(setOf(dueToday))
        val done = kept.next { board -> board.done.any { it.id == dueToday } }
        assertTrue(done.placed(DueGroup.Today).isEmpty())
        assertEquals(7, done.open)
    }

    // Read with the meetings it was made from: a due date moved comes with its new group, never with the old one.
    @Test
    fun aDueDateMovedComesWithItsNewGroup() = runBlocking {
        val now = LocalDateTime(2026, 10, 26, 10, 0).toInstant(paris)
        val kept = opened(now)
        val today = sampleBoard(now, paris).today
        val dueTomorrow = ActionId("sample-3", "0")
        kept.showBoard(today)
        kept.next { true }
        kept.setDue(dueTomorrow, today)
        val moved = kept.next { it.action(dueTomorrow)?.due == today }
        val dueToday = listOf(Placed("sample-4", "2", DueGroup.Today), Placed("sample-3", "0", DueGroup.Today))
        assertEquals(dueToday, moved.placed(DueGroup.Today))
        assertTrue(moved.placed(DueGroup.Tomorrow).isEmpty())
    }
}
