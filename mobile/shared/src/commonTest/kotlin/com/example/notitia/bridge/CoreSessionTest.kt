package com.example.notitia.bridge

import com.example.notitia.ai.Engine
import com.example.notitia.ai.EngineReply
import com.example.notitia.ai.EngineRequest
import com.example.notitia.ai.OfferedEngine
import com.example.notitia.ai.Said
import com.example.notitia.bridge.CoreEvent.Recorder.Phase
import com.example.notitia.meeting.Abilities
import com.example.notitia.meeting.RecorderState
import com.example.notitia.meeting.RecordingProblem
import com.example.notitia.meeting.meeting
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TRANSCRIBE = """{"type":"transcribe","language":"fr","previous":""}"""
private const val UNAVAILABLE = """{"type":"unavailable"}"""

// An engine of this app: it answers what the test says, and keeps what it was asked.
private class Lent(override val offered: OfferedEngine, var reply: EngineReply) : Engine {
    val asked = mutableListOf<EngineRequest>()

    override fun run(request: EngineRequest, sound: ByteArray): EngineReply = reply.also { asked += request }
}

private fun ear(reply: EngineReply = EngineReply.Heard(listOf(Said(Duration.ZERO, 900.milliseconds, "Bonjour à tous.")))) =
    Lent(OfferedEngine.Speech("ear", remote = false, longest = 30.seconds), reply)

private fun pen() = Lent(OfferedEngine.Text("pen", remote = true, contextTokens = 8_192, replyTokens = 1_024), EngineReply.Failed)

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

    // The engines given, through a session opened on a dispatcher the test runs, waiting on the gate before a retry.
    private class Offering(opens: Boolean = true) {
        val dispatcher = ManualDispatcher()
        val core = FakeCore(opens = opens)
        val engines = MutableSharedFlow<List<Engine>>(extraBufferCapacity = 8)
        val gate = Channel<Unit>()
        val session = CoreSession(core, "meetings", engines = engines, calls = dispatcher, beforeRetry = { gate.receive() })

        init {
            dispatcher.runAll()
        }

        fun offer(vararg lent: Engine) {
            engines.tryEmit(lent.toList())
            dispatcher.runAll()
        }

        fun offered(): List<List<String>> = core.offers.map { (engines, _) -> engines.map { it.name } }

        // The time to retry has come: false when no retry was waiting.
        fun retry(): Boolean {
            dispatcher.runAll()
            return gate.trySend(Unit).isSuccess.also { dispatcher.runAll() }
        }
    }

    @Test
    fun theAudioIsTidiedUpOnceTheCoreIsOpenThenAfterEachWaitByTheRuleOfTheCore() {
        val dispatcher = ManualDispatcher()
        val core = FakeCore()
        val hour = Channel<Unit>()
        CoreSession(core, "meetings", calls = dispatcher, beforeTidy = { hour.receive() })
        dispatcher.runAll()
        assertEquals(listOf(Change.Tidy), core.tidyUps)
        assertEquals("""{"type":"tidy"}""", CoreJson.encodeToString(CoreRequest.serializer(), core.tidyUps[0]))
        repeat(2) {
            assertTrue(hour.trySend(Unit).isSuccess)
            dispatcher.runAll()
        }
        assertEquals(3, core.tidyUps.size)
        assertTrue(core.applied.isEmpty(), "nothing the screens did not ask")
    }

    @Test
    fun aCoreThatCouldNotOpenIsNotTidiedUp() {
        val dispatcher = ManualDispatcher()
        val core = FakeCore(opens = false)
        CoreSession(core, "meetings", calls = dispatcher)
        dispatcher.runAll()
        assertTrue(core.tidyUps.isEmpty())
    }

    @Test
    fun theEnginesAreOfferedOnceTheCoreIsOpenThenAtEachEmissionAndWhatTheyCanDoIsTold() {
        val offering = Offering()
        assertEquals(emptyList(), offering.offered())
        assertEquals(Abilities.None, offering.session.abilities.value)
        val (ear, pen) = ear() to pen()
        offering.offer(ear)
        assertEquals(Abilities(hear = true, write = false), offering.session.abilities.value)
        offering.offer(ear, pen)
        assertEquals(Abilities(hear = true, write = true), offering.session.abilities.value)
        offering.offer()
        assertEquals(listOf(listOf("ear"), listOf("ear", "pen"), emptyList()), offering.offered())
        assertEquals(Abilities.None, offering.session.abilities.value)
        assertEquals(listOf(ear.offered, pen.offered), offering.core.offers[1].first)
    }

    @Test
    fun aCoreThatCouldNotOpenIsOfferedNothing() {
        val offering = Offering(opens = false)
        offering.offer(ear())
        assertEquals(emptyList(), offering.offered())
    }

    @Test
    fun theCoreRunsAnEngineByItsNameAndWhatThisAppCannotReadFails() {
        val offering = Offering()
        val ear = ear()
        offering.offer(ear, pen())
        val heard = """{"type":"heard","segments":[{"fromMs":0,"toMs":900,"text":"Bonjour à tous."}]}"""
        assertEquals(heard, offering.core.run("ear", TRANSCRIBE, ByteArray(2)))
        assertEquals(ENGINE_FAILED, offering.core.run("pen", """{"type":"complete","system":"","prompt":"","replyTokens":1}"""))
        assertEquals(ENGINE_FAILED, offering.core.run("gateway-transcribe", TRANSCRIBE))
        assertEquals(ENGINE_FAILED, offering.core.run("ear", "{"))
        assertEquals(ENGINE_FAILED, offering.core.run("ear", """{"type":"listen"}"""))
        assertEquals(listOf<EngineRequest>(EngineRequest.Transcribe("fr", previous = "")), ear.asked)
    }

    // The core asks an engine away nothing more until it is offered again: one retry, whatever asked, then each time
    // it is away again. An engine that answers asks for none.
    @Test
    fun anEngineAwayIsOfferedAgainOnceAfterAWhileTheLatestEnginesWithIt() {
        val offering = Offering()
        val ear = ear(EngineReply.Unavailable)
        offering.offer(ear)
        assertEquals(UNAVAILABLE, offering.core.run("ear", TRANSCRIBE))
        offering.core.run("ear", TRANSCRIBE)
        offering.dispatcher.runAll()
        assertEquals(1, offering.core.offers.size)
        val pen = pen()
        offering.offer(ear, pen)
        assertTrue(offering.retry())
        assertEquals(listOf(listOf("ear"), listOf("ear", "pen"), listOf("ear", "pen")), offering.offered())
        assertTrue(!offering.retry(), "one retry for both calls")
        offering.core.run("ear", TRANSCRIBE)
        assertTrue(offering.retry())
        assertEquals(4, offering.core.offers.size)
        ear.reply = EngineReply.Failed
        offering.core.run("ear", TRANSCRIBE)
        assertTrue(!offering.retry())
        assertEquals(4, offering.core.offers.size)
    }
}
