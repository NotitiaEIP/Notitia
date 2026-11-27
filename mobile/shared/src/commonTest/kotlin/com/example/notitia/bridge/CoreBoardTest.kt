package com.example.notitia.bridge

import com.example.notitia.meeting.ActionBoard
import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.meeting
import com.example.notitia.meeting.written
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate

// How the session follows the board of the core. Where each action goes is the core's rule, tested in Rust: the fake
// core places every one to complete.
class CoreBoardTest {
    private val day = LocalDate(2026, 11, 20)
    private val nextDay = LocalDate(2026, 11, 21)
    private val ticked = ActionId("recorded-1", "0")
    private val calls = ManualDispatcher()

    // On a dispatcher the test runs, as the real one: nothing runs inside the call that dispatched it.
    private fun meetings(core: FakeCore) = CoreMeetings(CoreSession(core, "meetings", calls = calls)).also { calls.runAll() }

    private fun FakeCore.boards() = applied.filterIsInstance<BoardAsk>()

    private fun CoreMeetings.show(today: LocalDate) {
        showBoard(today)
        calls.runAll()
    }

    @Test
    fun theBoardIsAskedForTheDayShownAndComesWithTheMeetingsHeldThen() {
        val core = FakeCore(meeting("recorded-1"))
        val kept = meetings(core)
        assertNull(kept.board.value)
        kept.show(day)
        assertEquals(listOf(BoardAsk(day)), core.boards())
        assertEquals(day, kept.board.value?.today)
        assertEquals(kept.meetings.value, kept.board.value?.meetings)
        kept.show(day)
        assertEquals(1, core.boards().size, "the same day asks nothing more")
        kept.show(nextDay)
        assertEquals(listOf(BoardAsk(day), BoardAsk(nextDay)), core.boards())
    }

    // Asked after the change, so the text it comes with is the changed one.
    @Test
    fun aChangeOfTheActionsAsksTheBoardAgainWithTheChangeInIt() {
        val core = FakeCore(meeting("recorded-1"))
        val kept = meetings(core)
        kept.show(day)
        kept.setDone(ticked, true)
        calls.runAll()
        assertEquals(2, core.boards().size)
        assertEquals(kept.meetings.value, kept.board.value?.meetings)
        val minutes = kept.board.value?.meetings?.single()?.minutes as MinutesState.Written
        assertTrue(minutes.minutes.actions.single().done)
    }

    // Recorded or heard, a meeting holds no action: its changes cost no board. One that held some moves it, even once
    // written again with none, and so does one that goes.
    @Test
    fun onlyAMeetingThatHeldOrHoldsActionsMovesTheBoard() {
        val core = FakeCore(meeting("recorded-1"), meeting("recorded-2", MinutesState.Writing))
        val kept = meetings(core)
        kept.show(day)
        fun told(event: CoreEvent): Int {
            core.tell(event)
            calls.runAll()
            return core.boards().size
        }
        assertEquals(1, told(CoreEvent.Changed(meeting("recorded-2", MinutesState.Writing).copy(duration = 40.minutes), at = 1)))
        assertEquals(2, told(CoreEvent.Changed(meeting("recorded-2"), at = 1)))
        assertEquals(3, told(CoreEvent.Changed(meeting("recorded-2", written()), at = 1)))
        assertEquals(3, told(CoreEvent.Changed(meeting("recorded-2", written()).copy(title = "Point"), at = 1)))
        assertEquals(4, told(CoreEvent.Removed(setOf("recorded-2"))))
        assertEquals(5, told(CoreEvent.Listed(listOf(meeting("recorded-1")))))
    }

    // Whatever changes before the board leaves is in it: one is asked, after the last change.
    @Test
    fun changesInARowAskOneBoardAfterTheLast() {
        val core = FakeCore(meeting("recorded-1"))
        val kept = meetings(core)
        kept.show(day)
        repeat(3) { core.tell(CoreEvent.Changed(meeting("recorded-1").copy(title = "Point $it"), at = 0)) }
        calls.runAll()
        assertEquals(2, core.boards().size)
        assertEquals("Point 2", kept.board.value?.meetings?.single()?.title)
    }

    // A change told while the board is being made, from the pipeline's thread say, asks for another.
    @Test
    fun aChangeWhileTheBoardIsMadeAsksForAnother() {
        val written = meeting("recorded-2")
        var minutesWritten = false
        val core = object : FakeCore(meeting("recorded-1")) {
            override fun apply(request: String): CoreFailure? {
                if (!minutesWritten && request.contains("\"board\"")) {
                    minutesWritten = true
                    tell(CoreEvent.Changed(written, at = 0))
                }
                return super.apply(request)
            }
        }
        val kept = meetings(core)
        kept.show(day)
        assertEquals(2, core.boards().size)
        assertEquals(listOf("recorded-2", "recorded-1"), kept.board.value?.meetings?.map { it.id })
    }

    // Made for a day the screens have left, at midnight say.
    @Test
    fun aBoardMadeForAnotherDayIsSetAside() {
        val core = FakeCore(meeting("recorded-1"))
        val kept = meetings(core)
        kept.show(day)
        kept.show(nextDay)
        core.tell(CoreEvent.Board(day, emptyList(), emptyList(), emptyList(), 0, 0, emptyList(), emptyMap()))
        assertEquals(nextDay, kept.board.value?.today)
    }

    // Kept in place before the tick is sent: every board asked after it carries it, until it is let go. The calls run
    // at once, as on the thread of the core while the screen goes on.
    @Test
    fun aTickedActionIsOnEveryBoardAskedAfterItUntilLetGo() {
        val core = FakeCore(meeting("recorded-1"))
        val kept = CoreMeetings(CoreSession(core, "meetings", calls = Dispatchers.Unconfined))
        // The tick sent, then every board asked after it.
        fun afterTick(done: Boolean): List<CoreRequest> {
            val tick = Change.Edit("recorded-1", MeetingEdit.SetDone("0", done))
            assertEquals(tick, core.applied.last { it is Change.Edit })
            return core.applied.takeLastWhile { it != tick }
        }
        kept.showBoard(day)
        kept.tick(ticked, done = true)
        assertEquals(setOf(ticked), kept.lingering.value)
        val ticking = afterTick(done = true)
        assertTrue(ticking.isNotEmpty() && ticking.all { it == BoardAsk(day, listOf(ticked)) }, "$ticking")
        kept.letGo(setOf(ticked))
        assertEquals(emptySet(), kept.lingering.value)
        assertEquals(BoardAsk(day), core.applied.last())
        kept.tick(ticked, done = false)
        val untick = afterTick(done = false)
        assertTrue(untick.isNotEmpty() && untick.all { it == BoardAsk(day) }, "$untick")
    }

    @Test
    fun aCoreThatCouldNotOpenShowsAnEmptyBoardAndIsAskedNothing() {
        val core = FakeCore(opens = false)
        val kept = meetings(core)
        kept.show(day)
        kept.tick(ticked, done = true)
        calls.runAll()
        assertEquals(ActionBoard.empty(day), kept.board.value)
        assertTrue(core.applied.isEmpty())
    }

    // Refused, or told in a shape this app cannot read: the app opens all the same, without actions, rather than wait
    // on its splash screen for a board that will not come.
    @Test
    fun aFirstBoardThatDoesNotComeStillLetsTheAppOpen() {
        val core = object : FakeCore(meeting("recorded-1")) {
            override fun apply(request: String): CoreFailure? =
                if (request.contains("\"board\"")) CoreFailure.Invalid else super.apply(request)
        }
        val kept = meetings(core)
        kept.show(day)
        assertEquals(ActionBoard.empty(day), kept.board.value)
    }

    @Test
    fun lettingGoOfNothingAsksNothing() {
        val core = FakeCore(meeting("recorded-1"))
        val kept = meetings(core)
        kept.show(day)
        kept.letGo(emptySet())
        kept.letGo(setOf(ticked))
        calls.runAll()
        assertEquals(1, core.boards().size)
    }

    // A group a later core would add: the board shown before stays.
    @Test
    fun aBoardThisAppCannotReadIsSkipped() {
        val core = FakeCore(meeting("recorded-1"))
        val kept = meetings(core)
        kept.show(day)
        val shown = kept.board.value
        core.tell(
            """{"type":"board","today":"2026-11-20","toComplete":[],"groups":[{"group":"overdue","actions":[]}],""" +
                """"done":[],"open":0,"late":0,"people":[],"lateByMeeting":{}}""",
        )
        assertSame(shown, kept.board.value)
    }
}
