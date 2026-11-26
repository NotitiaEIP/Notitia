package com.example.notitia.bridge

import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.files.Path

// Each fault of a bridge stops the self-check at its step, on the real core: only the core itself hears and writes up
// the meeting through the engines lent, which no fake could stand for. RustCoreTest proves a faithful one passes.
class CoreSelfCheckTest {
    private val folder = createTempDirectory("core-self-check").toFile()

    @AfterTest
    fun clear() {
        folder.deleteRecursively()
    }

    // Long enough for the core to hear and write a few seconds, short for the one awaited event that never comes.
    private fun check(core: NotitiaCore) = CoreSelfCheck(core, Path(folder.path), patience = 3.seconds).run()

    @Test
    fun aFolderThatCannotBeOpenedFailsAtOpen() {
        val refused = object : NotitiaCore by RustCore() {
            override fun open(directory: String, key: ByteArray?, listener: CoreListener) = CoreFailure.Unreadable
        }
        assertEquals("core bridge: failed at open", check(refused))
    }

    @Test
    fun aCoreThatTellsNothingFailsAtTheFirstEventAwaited() {
        val core = RustCore()
        val silent = object : NotitiaCore by core {
            override fun open(directory: String, key: ByteArray?, listener: CoreListener) = core.open(directory, key) {}
        }
        assertEquals("core bridge: failed at list", check(silent))
    }

    @Test
    fun aCoreThatRefusesTheEnginesFailsAtOffer() {
        val refusing = object : NotitiaCore by RustCore() {
            override fun offer(offered: String, engines: CoreEngines) = CoreFailure.Invalid
        }
        assertEquals("core bridge: failed at offer", check(refusing))
    }

    @Test
    fun aRecorderThatHearsNothingFailsAtRecord() {
        val deaf = object : NotitiaCore by RustCore() {
            override fun hear(track: Int, pcm: ByteArray) = Unit
        }
        assertEquals("core bridge: failed at record", check(deaf))
    }

    @Test
    fun enginesThatFailFailAtTheMinutes() {
        val core = RustCore()
        val failing = object : NotitiaCore by core {
            override fun offer(offered: String, engines: CoreEngines) = core.offer(offered) { _, _, _ -> ENGINE_FAILED }
        }
        assertEquals("core bridge: failed at minutes", check(failing))
    }

    @Test
    fun aCoreThatLosesTheLinesFailsAtRead() {
        val forgetful = object : NotitiaCore by RustCore() {
            override fun meeting(id: String) = CoreReply(text = null, CoreFailure.Unreadable)
        }
        assertEquals("core bridge: failed at read", check(forgetful))
    }

    @Test
    fun aCoreThatLosesTheSoundFailsAtSound() {
        val muted = object : NotitiaCore by RustCore() {
            override fun sound(id: String, fromMs: Long, lengthMs: Long) = CoreSound(pcm = ByteArray(32_000), failure = null)
        }
        assertEquals("core bridge: failed at sound", check(muted))
    }

    @Test
    fun aCoreThatTakesAnyTextForARequestFailsThere() {
        val core = RustCore()
        val lenient = object : NotitiaCore by core {
            override fun apply(request: String) = core.apply(request).takeUnless { it == CoreFailure.Invalid }
        }
        assertEquals("core bridge: failed at refused request", check(lenient))
    }

    // The core tells a change before it writes it: the check may end while the core still writes its last file in the
    // folder, which then cannot be removed. Saying how the check went must not throw, here nor in the iPhone app.
    @Test
    fun aFolderThatCannotBeClearedStillSaysHowTheCheckWent() {
        val core = RustCore()
        val writing = object : NotitiaCore by core {
            override fun open(directory: String, key: ByteArray?, listener: CoreListener): CoreFailure? {
                val opened = core.open(directory, key, listener)
                File(directory, "left/behind").mkdirs()
                return opened
            }
        }
        assertEquals("core bridge: ok", check(writing))
    }

    // The core tells from several threads: the minutes may be told before the recorder says it has stopped.
    @Test
    fun minutesToldBeforeTheRecorderStopsStillPass() {
        val core = RustCore()
        val early = object : NotitiaCore by core {
            override fun open(directory: String, key: ByteArray?, listener: CoreListener): CoreFailure? {
                val held = mutableListOf<String>()
                return core.open(directory, key) { event ->
                    val read = readEvent(event)
                    synchronized(held) {
                        when {
                            read is CoreEvent.Recorder && read.state == CoreEvent.Recorder.Phase.Idle -> held += event
                            else -> {
                                listener.onEvent(event)
                                if (read.isWritten()) held.forEach(listener::onEvent).also { held.clear() }
                            }
                        }
                    }
                }
            }
        }
        assertEquals("core bridge: ok", check(early))
    }

    // Or told only once they are there to read, just before the app asks for the next change.
    @Test
    fun minutesToldAfterTheyAreReadStillPass() {
        val core = RustCore()
        val held = mutableListOf<String>()
        var told: CoreListener? = null
        var released = false
        val late = object : NotitiaCore by core {
            override fun open(directory: String, key: ByteArray?, listener: CoreListener): CoreFailure? {
                told = listener
                return core.open(directory, key) { event ->
                    synchronized(held) { if (!released && readEvent(event).isWritten()) held += event else listener.onEvent(event) }
                }
            }

            // Read once the minutes are written, as a slow app would, and their event told right after.
            override fun meeting(id: String): CoreReply {
                var reply = core.meeting(id)
                repeat(500) {
                    if (reply.isWritten()) return@repeat
                    Thread.sleep(10)
                    reply = core.meeting(id)
                }
                synchronized(held) {
                    released = released || reply.isWritten()
                    held.forEach { told?.onEvent(it) }.also { held.clear() }
                }
                return reply
            }
        }
        assertEquals("core bridge: ok", check(late))
    }

    private fun CoreReply.isWritten() = text?.let { CoreJson.decodeFromString<Meeting>(it).minutes is MinutesState.Written } == true

    private fun CoreEvent?.isWritten() = this is CoreEvent.Changed && meeting.minutes is MinutesState.Written

    @Test
    fun aCoreThatThrowsIsReportedNotRaised() {
        val broken = object : NotitiaCore by RustCore() {
            override fun apply(request: String): CoreFailure? = error("bridge down")
        }
        assertEquals("core bridge: failed at exception", check(broken))
    }
}
