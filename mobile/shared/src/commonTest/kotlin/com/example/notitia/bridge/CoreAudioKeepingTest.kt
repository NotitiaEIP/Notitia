package com.example.notitia.bridge

import com.example.notitia.meeting.KeepAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

class CoreAudioKeepingTest {

    // Unconfined makes each call to the core at once, so the choice can be checked right after it is made.
    private fun keeping(core: NotitiaCore) =
        CoreAudioKeeping(CoreSession(core, directory = "meetings", calls = Dispatchers.Unconfined))

    @Test
    fun theChoiceIsShownAsTheCoreTellsItAndSentAsTheCoreReadsIt() {
        val core = FakeCore()
        val keeping = keeping(core)
        assertEquals(KeepAudio.Month, keeping.keepAudio.value)
        keeping.keep(KeepAudio.Day)
        assertEquals(KeepAudio.Day, keeping.keepAudio.value)
        assertEquals(listOf<CoreRequest>(Choice(KeepAudio.Day)), core.applied)
        assertEquals("""{"type":"settings","keepAudio":"day"}""", CoreJson.encodeToString(CoreRequest.serializer(), core.applied[0]))
    }

    @Test
    fun aChoiceThisAppDoesNotKnowIsSkippedAndTheLastOneKnownStays() {
        val core = FakeCore()
        val keeping = keeping(core)
        core.tell("""{"type":"settings","keepAudio":"week"}""")
        core.tell("""{"type":"settings","keepAudio":"fortnight"}""")
        assertEquals(KeepAudio.Week, keeping.keepAudio.value)
    }

    @Test
    fun aCoreThatCouldNotOpenShowsNoChoiceAndIsAskedNothing() {
        val core = FakeCore(opens = false)
        val keeping = keeping(core)
        keeping.keep(KeepAudio.UntilHeard)
        assertNull(keeping.keepAudio.value)
        assertTrue(core.applied.isEmpty())
    }
}
