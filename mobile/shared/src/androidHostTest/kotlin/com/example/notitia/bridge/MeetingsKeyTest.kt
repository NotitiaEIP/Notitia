package com.example.notitia.bridge

import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// The Keystore is the phone's: on the machine that builds, an AES key of the test stands for it, and can be lost.
class MeetingsKeyTest {
    private class Keystore(var wrapping: SecretKey? = null) : Wrapping {
        override fun existing() = wrapping

        override fun made(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { wrapping = it }
    }

    private val folder = createTempDirectory("meetings-key").toFile()
    private val file = File(folder, "meetings.key")
    private val keystore = Keystore()

    private fun key(syncFolder: (File) -> Unit = {}) = MeetingsKey(file, keystore, syncFolder)

    private fun names() = folder.list().orEmpty().sorted()

    @AfterTest
    fun clear() {
        folder.deleteRecursively()
    }

    @Test
    fun aKeyIsDrawnOnceThenReadBackTheSame() {
        val drawn = assertNotNull(key().read())
        assertEquals(32, drawn.size)
        assertContentEquals(drawn, key().read())
        assertEquals(listOf("meetings.key"), names())
    }

    @Test
    fun theFileHoldsTheVersionAndNothingInClear() {
        val drawn = assertNotNull(key().read())
        val envelope = file.readBytes()
        assertEquals(61, envelope.size)
        assertEquals(1, envelope[0].toInt())
        assertFalse(envelope.toList().windowed(8).any { it == drawn.take(8) })
    }

    // A newer app may have written it: no key now, and the file is left for that app.
    @Test
    fun anEnvelopeOfAnotherShapeIsLeftAsItIs() {
        key().read()
        val envelope = file.readBytes()
        val versionTwo = byteArrayOf(2) + envelope.copyOfRange(1, 61)
        for (unknown in listOf(envelope.copyOf(60), envelope + 0, versionTwo)) {
            file.writeBytes(unknown)
            assertNull(key().read())
            assertContentEquals(unknown, file.readBytes())
            assertEquals(listOf("meetings.key"), names())
        }
    }

    @Test
    fun aDamagedEnvelopeIsKeptAsideAndANewKeyDrawn() {
        val first = assertNotNull(key().read())
        val damaged = file.readBytes().also { it[30] = (it[30] + 1).toByte() }
        file.writeBytes(damaged)
        val second = assertNotNull(key().read())
        assertFalse(first.contentEquals(second))
        val aside = names().single { it.startsWith("meetings.key.lost-") }
        assertContentEquals(damaged, File(folder, aside).readBytes())
        assertContentEquals(second, key().read())
    }

    @Test
    fun aKeyWhoseWrappingIsGoneForGoodIsReplacedAndTheOldEnvelopeKeptAside() {
        val first = assertNotNull(key().read())
        val envelope = file.readBytes()
        keystore.wrapping = null
        val second = assertNotNull(key().read())
        assertFalse(first.contentEquals(second))
        val aside = names().single { it.startsWith("meetings.key.lost-") }
        assertContentEquals(envelope, File(folder, aside).readBytes())
        assertContentEquals(second, key().read())
    }

    @Test
    fun aKeyThatCannotBeWrittenIsNotGiven() {
        // A folder where the file is written first: the disk refuses it, whoever runs the tests.
        File(folder, "meetings.key.tmp/taken").apply { parentFile.mkdirs() }.writeText("")
        assertNull(key().read())
        assertFalse(file.exists())
    }

    // Its folder not on the disk, a power cut could lose the file: the key waits for the next launch.
    @Test
    fun aKeyWhoseFolderDidNotReachTheDiskIsGivenOnlyAtTheNextLaunch() {
        assertNull(key(syncFolder = { throw IOException("refused") }).read())
        assertNotNull(key().read())
    }

    @Test
    fun aWriteCutShortIsForgotten() {
        File(folder, "meetings.key.tmp").writeBytes(ByteArray(10))
        assertNotNull(key().read())
        assertEquals(listOf("meetings.key"), names())
    }

    // Sealed once and for all by another library, as the phone seals it: the bytes 0 to 31 under the key 32 to 63,
    // made up. Version 1 opens whatever this code becomes.
    @Test
    fun theFirstVersionOfTheFileStillOpens() {
        val sealed = "01404142434445464748494a4bc255bf825473c30c5a3b5a2389475f7d039f8f90e155b1c976e78e1b7cf7d1" +
            "1dfb2852044882b3a6a1b5c0481aee1d66"
        file.writeBytes(sealed.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val wrapping = SecretKeySpec(ByteArray(32) { (32 + it).toByte() }, "AES")
        assertContentEquals(ByteArray(32) { it.toByte() }, MeetingsKey(file, Keystore(wrapping), syncFolder = {}).read())
    }
}
