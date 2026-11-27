package com.example.notitia.bridge

import com.example.notitia.ai.Engine
import com.example.notitia.ai.EngineReply
import com.example.notitia.ai.EngineRequest
import com.example.notitia.ai.OfferedEngine
import com.example.notitia.ai.Said
import com.example.notitia.meeting.Abilities
import com.example.notitia.meeting.KeepAudio
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.RecorderState
import com.example.notitia.recording.audio
import java.io.File
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Engines of this app in its own types: an ear that hears the same sentence in every window, and a pen that writes it
// up as a model does, the time copied from the line it read.
private val ear = object : Engine {
    override val offered = OfferedEngine.Speech("ear", remote = false, longest = 30.seconds)

    override fun run(request: EngineRequest, sound: ByteArray) =
        EngineReply.Heard(listOf(Said(Duration.ZERO, 900.milliseconds, "Lucas prépare la démo.")))
}

private val pen = object : Engine {
    override val offered = OfferedEngine.Text("pen", remote = false, contextTokens = 4_096, replyTokens = 1_024)

    override fun run(request: EngineRequest, sound: ByteArray): EngineReply {
        val time = (request as EngineRequest.Complete).prompt.substringAfter("[").substringBefore("]")
        return EngineReply.Written("SUMMARY: Point sur la démo.\nACTION: Préparer la démo | Lucas | - | $time")
    }
}

// The engines of this app, offered to the real core through the bridge of the phone: what the fake core cannot prove,
// that the core reads the offer this app writes and tells the engines it chose. Which one it chooses is tested in
// Rust, where the rule lives.
class RustEnginesTest {
    private val folder = createTempDirectory("rust-engines").toFile()
    private val examples = CoreJson.parseToJsonElement(File("../../core/tests/fixtures/bridge/engines.json").readText())
    private val none = CoreEngines { _, _, _ -> ENGINE_FAILED }

    @AfterTest
    fun clear() {
        folder.deleteRecursively()
    }

    @Test
    fun anOfferCrossesAndTheCoreTellsTheEnginesItChose() {
        val told = Collections.synchronizedList(mutableListOf<JsonElement>())
        val core = RustCore()
        assertNull(core.open(folder.path, key = null) { told += CoreJson.parseToJsonElement(it) })
        val events = examples.jsonObject.getValue("events").jsonArray
        assertEquals(events[0], told.last())

        assertNull(core.offer(examples.jsonObject.getValue("offer").toString(), none))
        assertEquals(events[1], told.last())
        assertNull(core.offer("[]", none))
        assertEquals(events[0], told.last())
    }

    // The core calls the engine from a thread of its own, which JNA attaches to the JVM for the call: what no other
    // test reaches. Through RustCore alone, in the text of the bridge.
    @Test
    fun aMeetingRecordedIsHeardOnAThreadOfTheCoreByAnEngineOfThisApp() = runBlocking {
        val told = Collections.synchronizedList(mutableListOf<JsonObject>())
        val core = RustCore()
        assertNull(core.open(folder.path, ByteArray(KEY_BYTES) { 7 }) { told += CoreJson.parseToJsonElement(it).jsonObject })
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val ear = CoreEngines { _, request, sound ->
            asked += request
            check(sound.size % 2 == 0 && sound.size <= 30 * 32_000)
            """{"type":"heard","segments":[{"fromMs":0,"toMs":900,"text":"Bonjour à tous."}]}"""
        }
        assertNull(core.offer("""[{"kind":"speech","name":"ear","remote":false,"longestMs":30000}]""", neverThrowing(ear)))
        assertNull(core.apply("""{"type":"start","tracks":1,"offsetMinutes":60}"""))
        // Two seconds said between two silences: heard as speech, against the noise learned from the silence.
        core.hear(0, audio(1_000, level = 0) + audio(2_000) + audio(2_500, level = 0))
        assertNull(core.apply("""{"type":"stop"}"""))
        val heard = withTimeout(20.seconds) {
            var meeting: String? = null
            while (meeting == null) {
                meeting = synchronized(told) { told.firstNotNullOfOrNull { it["meeting"]?.jsonPrimitive?.content } }
                if (meeting == null) delay(10)
            }
            // Whole once it no longer says how far it is heard.
            var whole = CoreJson.parseToJsonElement(checkNotNull(core.meeting(meeting).text)).jsonObject
            while ("transcribedMs" in whole) {
                delay(10)
                whole = CoreJson.parseToJsonElement(checkNotNull(core.meeting(meeting).text)).jsonObject
            }
            whole
        }
        val lines = heard.getValue("transcript").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
        assertEquals(listOf("Bonjour à tous."), lines)
        assertEquals("""{"type":"transcribe","language":"fr","previous":""}""", asked.single())
    }

    // The minutes as well, by a text engine of this app on the same thread of the core: the request this app reads, and
    // the reply the core reads back into the meeting.
    @Test
    fun theMinutesOfAMeetingRecordedAreWrittenOnAThreadOfTheCoreByAnEngineOfThisApp() = runBlocking {
        val core = RustCore()
        val told = Collections.synchronizedList(mutableListOf<JsonObject>())
        assertNull(core.open(folder.path, ByteArray(KEY_BYTES) { 7 }) { told += CoreJson.parseToJsonElement(it).jsonObject })
        val asked = Collections.synchronizedList(mutableListOf<JsonObject>())
        val engines = CoreEngines { engine, request, _ ->
            if (engine == "ear") {
                """{"type":"heard","segments":[{"fromMs":0,"toMs":900,"text":"Lucas prépare la démo."}]}"""
            } else {
                val read = CoreJson.parseToJsonElement(request).jsonObject.also { asked += it }
                // As a model does: the time copied from the line it read.
                val time = read.getValue("prompt").jsonPrimitive.content.substringAfter("[").substringBefore("]")
                """{"type":"written","text":"SUMMARY: Point sur la démo.\nACTION: Préparer la démo | Lucas | - | $time"}"""
            }
        }
        val offer = """[{"kind":"speech","name":"ear","remote":false,"longestMs":30000},""" +
            """{"kind":"text","name":"pen","remote":false,"contextTokens":4096,"replyTokens":1024}]"""
        assertNull(core.offer(offer, neverThrowing(engines)))
        assertNull(core.apply("""{"type":"start","tracks":1,"offsetMinutes":60}"""))
        core.hear(0, audio(1_000, level = 0) + audio(2_000) + audio(2_500, level = 0))
        assertNull(core.apply("""{"type":"stop"}"""))
        val written = withTimeout(20.seconds) {
            var meeting: String? = null
            while (meeting == null) {
                meeting = synchronized(told) { told.firstNotNullOfOrNull { it["meeting"]?.jsonPrimitive?.content } }
                if (meeting == null) delay(10)
            }
            var minutes = CoreJson.parseToJsonElement(checkNotNull(core.meeting(meeting).text)).jsonObject.getValue("minutes")
            while (minutes.jsonObject.getValue("type").jsonPrimitive.content != "written") {
                delay(10)
                minutes = CoreJson.parseToJsonElement(checkNotNull(core.meeting(meeting).text)).jsonObject.getValue("minutes")
            }
            minutes.jsonObject.getValue("minutes").jsonObject
        }
        assertEquals("Point sur la démo.", written.getValue("summary").jsonPrimitive.content)
        val action = written.getValue("actions").jsonArray.single().jsonObject
        assertEquals("Lucas", action.getValue("owner").jsonPrimitive.content)
        assertEquals("Lucas prépare la démo.", action.getValue("source").jsonObject.getValue("quote").jsonPrimitive.content)
        val request = asked.single()
        assertEquals("complete", request.getValue("type").jsonPrimitive.content)
        assertEquals(1_024, request.getValue("replyTokens").jsonPrimitive.content.toInt())
        assertTrue("] Lucas prépare la démo." in request.getValue("prompt").jsonPrimitive.content)
    }

    // The whole of this app on the real core: its engines, in its own types, lent through the session, and a meeting
    // recorded that comes back heard and written up, its action pointing to the sentence heard.
    @Test
    fun aMeetingRecordedThroughTheSessionIsHeardAndWrittenUpByTheEnginesOfThisApp() = runBlocking {
        val session = CoreSession(RustCore(), folder.path, key = { ByteArray(KEY_BYTES) { 7 } }, engines = flowOf(listOf(ear, pen)))
        withTimeout(20.seconds) {
            val meeting = recordedAndWrittenUp(session)
            assertNull(meeting.transcribed)
            val action = (meeting.minutes as MinutesState.Written).minutes.actions.single()
            assertEquals(Triple("Préparer la démo", "Lucas", "Lucas prépare la démo."), Triple(action.text, action.owner, action.source?.quote))
            assertNull(meeting.audioErasedAt, "kept a month by default")
        }
    }

    // The choice of the user crosses to the real core, which keeps it and erases the audio once the meeting is heard
    // whole: its minutes are written all the same, from its lines.
    @Test
    fun untilTranscribedTheAudioGoesOnceTheMeetingIsHeardAndItsMinutesAreStillWritten() = runBlocking {
        val session = CoreSession(RustCore(), folder.path, key = { ByteArray(KEY_BYTES) { 7 } }, engines = flowOf(listOf(ear, pen)))
        val keeping = CoreAudioKeeping(session)
        withTimeout(20.seconds) {
            keeping.keepAudio.first { it == KeepAudio.Month }
            keeping.keep(KeepAudio.UntilHeard)
            keeping.keepAudio.first { it == KeepAudio.UntilHeard }
            val meeting = recordedAndWrittenUp(session)
            assertNotNull(meeting.audioErasedAt)
            assertEquals("Point sur la démo.", (meeting.minutes as MinutesState.Written).minutes.summary)
            val kept = folder.list().orEmpty().filter { it.endsWith(".notitia") || it.endsWith(".audio") }
            assertEquals(listOf("${meeting.id}.notitia"), kept)
        }
    }

    // Two seconds said between two silences, then stopped: the meeting once its minutes are written.
    private suspend fun recordedAndWrittenUp(session: CoreSession): Meeting {
        session.abilities.first { it == Abilities(hear = true, write = true) }
        session.send(Command.Start(tracks = 1, offsetMinutes = 60))
        session.recorder.first { it is RecorderState.Recording }
        session.hear(audio(1_000, level = 0) + audio(2_000) + audio(2_500, level = 0))
        session.send(Command.Stop)
        return checkNotNull(session.meetings.map { it?.singleOrNull() }.first { it?.minutes is MinutesState.Written })
    }

    @Test
    fun anOfferTheCoreWouldHaveToCorrectIsRefused() {
        val core = RustCore()
        assertNull(core.open(folder.path, key = null) {})
        val withoutRemote = """[{"kind":"speech","name":"s","longestMs":30000}]"""
        for (wrong in listOf("", "{}", withoutRemote)) assertEquals(CoreFailure.Invalid, core.offer(wrong, none))
    }
}
