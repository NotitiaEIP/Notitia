package com.example.notitia.bridge

import com.example.notitia.bridge.CoreEvent.Recorder.Phase
import com.example.notitia.meeting.RecorderState
import com.example.notitia.meeting.RecordingProblem
import com.example.notitia.meeting.meeting
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

    @Test
    fun theRecorderIsFollowedStepByStepAsTheCoreTellsIt() {
        val dispatcher = ManualDispatcher()
        val core = FakeCore()
        val session = CoreSession(core, directory = "meetings", key = { ByteArray(32) }, calls = dispatcher)
        val steps = mutableListOf<RecorderState>()
        CoroutineScope(dispatcher).launch(start = CoroutineStart.UNDISPATCHED) { session.recorderChanges.collect { steps += it } }
        dispatcher.runAll()
        val started = CoreEvent.Recorder(Phase.Recording, startedAt = fakeStart)
        core.tell(started)
        core.tell(started.copy(recorded = 100.milliseconds, level = 0.5f))
        // An active state without its start is no state: skipped, as an event this app cannot read.
        core.tell(CoreEvent.Recorder(Phase.Paused))
        core.tell(CoreEvent.Recorder(Phase.Idle))
        dispatcher.runAll()
        val heard = listOf(RecorderState.Recording(fakeStart, Duration.ZERO, 0f), RecorderState.Recording(fakeStart, 100.milliseconds, 0.5f))
        assertEquals(heard + RecorderState.Idle, steps)
        assertEquals(RecorderState.Idle, session.recorder.value)
    }

    @Test
    fun aCoreThatCouldNotOpenAnswersTheRecorderItself() {
        val session = CoreSession(FakeCore(opens = false), directory = "meetings", calls = Dispatchers.Unconfined)
        session.send(Command.Pause)
        assertEquals(RecorderState.Idle, session.recorder.value)
        session.send(Command.Start(tracks = 1, offsetMinutes = 60))
        assertEquals(RecorderState.Failed(RecordingProblem.Unavailable), session.recorder.value)
        session.send(Command.Dismiss)
        assertEquals(RecorderState.Idle, session.recorder.value)
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
