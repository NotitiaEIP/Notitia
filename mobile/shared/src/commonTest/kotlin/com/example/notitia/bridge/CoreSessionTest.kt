package com.example.notitia.bridge

import com.example.notitia.meeting.meeting
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CoreSessionTest {

    @Test
    fun theKeyOfTheAppGoesToTheCoreAndIsWipedOnceHeld() {
        val given = ByteArray(32) { (it + 1).toByte() }
        val core = FakeCore()
        CoreSession(core, directory = "meetings", key = { given }, calls = Dispatchers.Unconfined)
        assertContentEquals(ByteArray(32) { (it + 1).toByte() }, core.key)
        assertContentEquals(ByteArray(32), given)
    }

    @Test
    fun aKeyOfTheWrongSizeIsNoKeyAndIsWipedAllTheSame() {
        val given = ByteArray(31) { 1 }
        val core = FakeCore(meeting("recorded-1"))
        val session = CoreSession(core, directory = "meetings", key = { given }, calls = Dispatchers.Unconfined)
        assertNull(core.key)
        assertContentEquals(ByteArray(31), given)
        assertEquals(listOf("recorded-1"), session.meetings.value?.map { it.id })
    }

    @Test
    fun aKeyThatCannotBeReadStillOpensTheMeetings() {
        val core = FakeCore(meeting("recorded-1"))
        val session = CoreSession(core, directory = "meetings", key = { error("Keystore down") }, calls = Dispatchers.Unconfined)
        assertNull(core.key)
        assertEquals(listOf("recorded-1"), session.meetings.value?.map { it.id })
    }

    // The core tells them one at a time, from the thread of a call or the recorder's. Were they told at once, none
    // would be lost, and the list shown would hold them all.
    @Test
    fun changesToldFromSeveralThreadsAreAllKeptAndShown() {
        val core = FakeCore()
        val session = CoreSession(core, directory = "meetings", calls = Dispatchers.Unconfined)
        runBlocking(Dispatchers.Default) {
            repeat(4) { thread ->
                launch { repeat(50) { core.tell(CoreEvent.Changed(meeting("recorded-$thread-$it"), at = 0)) } }
            }
        }
        assertEquals(200, session.meetings.value?.size)
    }
}
