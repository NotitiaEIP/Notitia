package com.example.notitia.bridge

import com.example.notitia.meeting.meeting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CoreSessionTest {

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
