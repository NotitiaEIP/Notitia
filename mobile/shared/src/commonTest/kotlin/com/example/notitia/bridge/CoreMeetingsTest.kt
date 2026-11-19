package com.example.notitia.bridge

import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesDraft
import com.example.notitia.meeting.TranscriptLine
import com.example.notitia.meeting.meeting
import com.example.notitia.ui.search.runNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate

class CoreMeetingsTest {

    // Unconfined makes each call to the core at once, so the list can be checked right after a change.
    private fun meetings(core: NotitiaCore, samples: List<Meeting> = emptyList()) =
        CoreMeetings(CoreSession(core, directory = "meetings", samples = samples, calls = Dispatchers.Unconfined))

    private fun CoreMeetings.ids() = meetings.value?.map { it.id }

    private fun later(id: String) = meeting(id).let { it.copy(startedAt = it.startedAt + 1.days) }

    @Test
    fun theMeetingsOfTheFolderAndTheSamplesAreShownTogether() {
        val core = FakeCore(meeting("recorded-1"))
        val kept = meetings(core, samples = listOf(later("sample-1")))
        assertEquals(listOf("sample-1", "recorded-1"), kept.ids())
        assertEquals(listOf<CoreRequest>(Change.Lend(listOf(later("sample-1")))), core.applied)
    }

    @Test
    fun aFolderThatCannotBeReadStillLetsTheAppOpen() {
        val core = FakeCore(opens = false)
        val kept = meetings(core, samples = listOf(meeting("sample-1")))
        kept.rename("sample-1", "Point produit")
        assertEquals(emptyList(), kept.meetings.value)
        assertTrue(core.applied.isEmpty())
    }

    @Test
    fun eachChangeGoesToTheCoreAsAValue() {
        val core = FakeCore(meeting("recorded-1"))
        val kept = meetings(core)
        val action = ActionId("recorded-1", "0")
        val day = LocalDate(2026, 11, 20)
        val started = meeting("recorded-9").startedAt
        val lines = listOf(TranscriptLine(Duration.ZERO, "Bonjour à tous."))
        kept.rename("recorded-1", "Point produit")
        kept.setDone(action, true)
        kept.setOwner(action, "Nathan")
        kept.setDue(action, day)
        kept.setReminder(action, true)
        kept.keep("recorded-9", started, 1.minutes, lines, MinutesDraft.Writing)
        kept.delete(setOf("recorded-1", "recorded-9"))
        fun edit(edit: MeetingEdit) = Change.Edit("recorded-1", edit)
        assertEquals(
            listOf<CoreRequest>(
                edit(MeetingEdit.Rename("Point produit")),
                edit(MeetingEdit.SetDone("0", true)),
                edit(MeetingEdit.SetOwner("0", "Nathan")),
                edit(MeetingEdit.SetDue("0", day)),
                edit(MeetingEdit.SetReminder("0", true)),
                Change.Record("recorded-9", started, 1.minutes, lines, MinutesDraft.Writing),
                Change.Delete(setOf("recorded-1", "recorded-9")),
            ),
            core.applied,
        )
        assertEquals(emptyList(), kept.meetings.value)
    }

    @Test
    fun aChangedMeetingIsShownAsTheCoreTellsIt() {
        val kept = meetings(FakeCore(meeting("recorded-1")))
        kept.rename("recorded-1", "Point produit")
        assertEquals(listOf(meeting("recorded-1").copy(title = "Point produit")), kept.meetings.value)
    }

    @Test
    fun aMeetingTakesThePlaceTheCoreGivesIt() {
        val core = FakeCore(meeting("recorded-2"))
        val kept = meetings(core)
        core.tell(CoreEvent.Changed(meeting("recorded-3"), at = 1))
        core.tell(CoreEvent.Changed(meeting("recorded-1"), at = 0))
        core.tell(CoreEvent.Changed(later("recorded-4"), at = 0))
        assertEquals(listOf("recorded-4", "recorded-1", "recorded-2", "recorded-3"), kept.ids())
        // Known already: it leaves its place for the new one. A place past the end is the end.
        core.tell(CoreEvent.Changed(meeting("recorded-4"), at = 9))
        assertEquals(listOf("recorded-1", "recorded-2", "recorded-3", "recorded-4"), kept.ids())
        core.tell(CoreEvent.Changed(later("recorded-4"), at = 0))
        core.tell(CoreEvent.Removed(setOf("recorded-1", "recorded-3")))
        assertEquals(listOf("recorded-4", "recorded-2"), kept.ids())
    }

    @Test
    fun aFileLeftBehindIsToldUntilItIsWritten() {
        val core = FakeCore(meeting("recorded-1"))
        val kept = meetings(core)
        assertEquals(emptySet(), kept.unsaved.value)
        core.tell(CoreEvent.Unsaved(setOf("recorded-1")))
        assertEquals(setOf("recorded-1"), kept.unsaved.value)
        core.tell(CoreEvent.Unsaved(emptySet()))
        assertEquals(emptySet(), kept.unsaved.value)
    }

    @Test
    fun anEventThisAppCannotReadIsSkipped() {
        val core = FakeCore(meeting("recorded-1"))
        val kept = meetings(core)
        core.tell("{\"type\":\"merged\",\"meetings\":[]}")
        core.tell("{\"type\":\"changed\"")
        assertEquals(listOf("recorded-1"), kept.ids())
    }

    @Test
    fun theLinesOfAMeetingAreReadWhenAsked() {
        val heard = meeting("recorded-1").copy(transcript = listOf(TranscriptLine(Duration.ZERO, "Bonjour à tous.")))
        val kept = meetings(FakeCore(heard))
        assertEquals(emptyList(), kept.meetings.value?.single()?.transcript)
        assertEquals(heard.transcript, runNow { kept.transcript("recorded-1") })
        assertNull(runNow { kept.transcript("recorded-7") })
    }
}
