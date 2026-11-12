package com.example.notitia.storage

import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.WrittenMinutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers

class MeetingRepositoryTest {

    private class MemoryStore(vararg meetings: Meeting) : MeetingStore {
        val saved = meetings.associateBy { it.id }.toMutableMap()
        val deleted = mutableListOf<String>()
        var reads = true

        override fun device() = "phone-1"
        override fun loadAll() = if (reads) saved.values.toList() else error("unreadable")
        override fun save(meeting: Meeting) {
            saved[meeting.id] = meeting
        }
        override fun delete(id: String) {
            saved -= id
            deleted += id
        }
    }

    private val now = Instant.parse("2026-11-11T08:00:00Z")
    private val clock = object : Clock {
        override fun now() = now
    }

    // Unconfined runs each write at once, so the store can be checked right after a change.
    private fun repository(store: MeetingStore, samples: List<Meeting> = emptyList()) =
        MeetingRepository(store, samples, clock, Dispatchers.Unconfined)

    @Test
    fun theStoredMeetingsAndTheSamplesAreShown() {
        val repository = repository(MemoryStore(meeting("recorded-1")), samples = listOf(meeting("sample-1")))
        assertEquals(listOf("recorded-1", "sample-1"), repository.meetings.value?.map { it.id })
        assertEquals("phone-1", repository.device)
    }

    @Test
    fun minutesLeftWritingByAKilledAppHaveFailed() {
        val repository = repository(MemoryStore(meeting("recorded-1", MinutesState.Writing)))
        assertEquals(MinutesState.Failed, repository.meetings.value?.single()?.minutes)
    }

    @Test
    fun anUnreadableFolderStillLetsTheAppOpen() {
        val repository = repository(MemoryStore().apply { reads = false })
        assertEquals(emptyList(), repository.meetings.value)
    }

    @Test
    fun aChangedActionIsShownAndWritten() {
        val store = MemoryStore(meeting("recorded-1"))
        val repository = repository(store)
        repository.updateAction(ActionId("recorded-1", 0)) { it.copy(done = true, owner = "Nathan") }
        val action = MinutesAction("Préparer la démo", owner = "Nathan", due = null, done = true)
        val changed = meeting("recorded-1", MinutesState.Written(WrittenMinutes("", emptyList(), listOf(action))))
            .copy(modifiedAt = now)
        assertEquals(listOf(changed), repository.meetings.value)
        assertEquals(changed, store.saved["recorded-1"])
    }

    @Test
    fun aSampleChangesOnScreenButIsNeverWritten() {
        val store = MemoryStore()
        val repository = repository(store, samples = listOf(meeting("sample-1")))
        repository.updateAction(ActionId("sample-1", 0)) { it.copy(done = true) }
        val minutes = repository.meetings.value?.single()?.minutes
        assertEquals(true, (minutes as MinutesState.Written).minutes.actions.single().done)
        assertNull(store.saved["sample-1"])
    }

    @Test
    fun aNewMeetingComesFirst() {
        val repository = repository(MemoryStore(meeting("recorded-1")))
        repository.save(meeting("recorded-2"))
        assertEquals(listOf("recorded-2", "recorded-1"), repository.meetings.value?.map { it.id })
    }

    @Test
    fun aRenamedMeetingIsShownAndWritten() {
        val store = MemoryStore(meeting("recorded-1"))
        val repository = repository(store)
        repository.rename("recorded-1", "  Point produit  ")
        val renamed = meeting("recorded-1").copy(title = "Point produit", modifiedAt = now)
        assertEquals(listOf(renamed), repository.meetings.value)
        assertEquals(renamed, store.saved["recorded-1"])
    }

    @Test
    fun aBlankOrUnchangedTitleChangesNothing() {
        val titled = meeting("recorded-1").copy(title = "Point produit")
        val store = MemoryStore(titled)
        val repository = repository(store)
        repository.rename("recorded-1", "   ")
        repository.rename("recorded-1", "Point produit ")
        assertEquals(listOf(titled), repository.meetings.value)
        assertEquals(titled, store.saved["recorded-1"])
    }

    @Test
    fun aDeletedMeetingLeavesTheListAndTheDisk() {
        val store = MemoryStore(meeting("recorded-1"), meeting("recorded-2"))
        val repository = repository(store)
        repository.delete("recorded-1")
        assertEquals(listOf("recorded-2"), repository.meetings.value?.map { it.id })
        assertEquals(listOf("recorded-1"), store.deleted)
    }

    @Test
    fun aMeetingStillTranscribedDoesNotComeBackOnceDeleted() {
        val store = MemoryStore(meeting("recorded-1"))
        val repository = repository(store)
        repository.delete("recorded-1")
        repository.save(meeting("recorded-1", written("Préparer la démo", "Envoyer le compte rendu")))
        assertEquals(emptyList(), repository.meetings.value)
        assertNull(store.saved["recorded-1"])
    }

    @Test
    fun aDeletedSampleTouchesNothingOnDisk() {
        val store = MemoryStore()
        val repository = repository(store, samples = listOf(meeting("sample-1")))
        repository.delete("sample-1")
        assertEquals(emptyList(), repository.meetings.value)
        assertTrue(store.deleted.isEmpty())
    }
}
