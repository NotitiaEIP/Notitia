package com.example.notitia.storage

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

class FileMeetingStoreTest {

    private val directory = Path(SystemTemporaryDirectory, "notitia-test-${Random.nextLong().toULong()}")
    private val store = FileMeetingStore(directory)

    @AfterTest
    fun clean() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun aSavedMeetingIsReadBack() {
        store.save(meeting("recorded-1"))
        assertEquals(listOf(meeting("recorded-1")), FileMeetingStore(directory).loadAll())
    }

    @Test
    fun savingAgainReplacesTheMeeting() {
        store.save(meeting("recorded-1"))
        store.save(meeting("recorded-1", written("Envoyer le compte rendu")))
        assertEquals(listOf(meeting("recorded-1", written("Envoyer le compte rendu"))), store.loadAll())
    }

    @Test
    fun aDamagedFileHidesOnlyItsOwnMeetingAndStaysOnDisk() {
        store.save(meeting("recorded-1"))
        val damaged = Path(directory, "recorded-2.notitia")
        SystemFileSystem.sink(damaged).buffered().use { it.writeString("{\"version\":") }
        assertEquals(listOf(meeting("recorded-1")), store.loadAll())
        assertTrue(SystemFileSystem.exists(damaged))
    }

    @Test
    fun nothingSavedYetMeansNoMeeting() {
        assertEquals(emptyList(), store.loadAll())
    }

    @Test
    fun theDeviceKeepsTheSameIdentifier() {
        assertEquals(store.device(), FileMeetingStore(directory).device())
    }

    @Test
    fun aDeletedMeetingIsNotReadBack() {
        store.save(meeting("recorded-1"))
        store.save(meeting("recorded-2"))
        store.delete("recorded-1")
        assertEquals(listOf(meeting("recorded-2")), FileMeetingStore(directory).loadAll())
    }

    @Test
    fun deletingAMeetingAlsoDeletesADraftLeftByAKilledApp() {
        store.save(meeting("recorded-1"))
        val draft = Path(directory, "recorded-1.notitia.tmp")
        SystemFileSystem.sink(draft).buffered().use { it.writeString("{\"version\":") }
        store.delete("recorded-1")
        assertFalse(SystemFileSystem.exists(draft))
    }

    @Test
    fun deletingAMeetingThatIsNotThereIsFine() {
        store.delete("recorded-1")
        assertEquals(emptyList(), store.loadAll())
    }
}
