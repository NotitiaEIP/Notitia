package com.example.notitia.bridge

import com.example.notitia.storage.MeetingCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

// Answers as the Rust core must, with the Kotlin codec the core replaces.
private open class KotlinCore : NotitiaCore {
    override fun meetingSummary(path: String): CoreResult<MeetingSummary> {
        val file = Path(path)
        if (!SystemFileSystem.exists(file)) return CoreFailed(CoreFailure.Unreadable)
        val bytes = SystemFileSystem.source(file).buffered().use { it.readByteArray() }
        val meeting = MeetingCodec.decode(bytes) ?: return CoreFailed(CoreFailure.NewerFormat)
        return CoreOk(MeetingSummary(meeting.id, meeting.title, meeting.startedAt.toEpochMilliseconds()))
    }

    override fun ask(host: HostProbe, question: String): String = host.answer(question)
}

class CoreSelfCheckTest {

    @Test
    fun aFaithfulCorePasses() {
        assertEquals("core bridge: ok", CoreSelfCheck(KotlinCore()).run())
    }

    @Test
    fun aCoreThatMisreadsTheMeetingFailsAtRead() {
        val core = object : KotlinCore() {
            override fun meetingSummary(path: String) = CoreOk(MeetingSummary("other", title = null, startedAtMs = 0))
        }
        assertEquals("core bridge: failed at read", CoreSelfCheck(core).run())
    }

    @Test
    fun aCoreThatReadsANewerFormatFailsThere() {
        val core = object : KotlinCore() {
            override fun meetingSummary(path: String) =
                if (path.endsWith("newer.notitia")) CoreFailed(CoreFailure.NotAMeeting) else super.meetingSummary(path)
        }
        assertEquals("core bridge: failed at newer format", CoreSelfCheck(core).run())
    }

    @Test
    fun aCoreThatNeverCallsTheHostFailsAtHost() {
        val core = object : KotlinCore() {
            override fun ask(host: HostProbe, question: String) = question
        }
        assertEquals("core bridge: failed at host", CoreSelfCheck(core).run())
    }

    @Test
    fun aCoreThatThrowsIsReportedNotRaised() {
        val core = object : KotlinCore() {
            override fun ask(host: HostProbe, question: String): String = error("bridge down")
        }
        assertEquals("core bridge: failed at exception", CoreSelfCheck(core).run())
    }
}
