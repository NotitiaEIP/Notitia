package com.example.notitia.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class CoreSelfCheckTest {

    // The fake tells every event before it returns: nothing to wait for but the failures.
    private fun check(core: NotitiaCore) = CoreSelfCheck(core, patience = 50.milliseconds).run()

    @Test
    fun aFaithfulCorePasses() {
        assertEquals("core bridge: ok", check(FakeCore()))
    }

    @Test
    fun aFolderThatCannotBeOpenedFailsAtOpen() {
        assertEquals("core bridge: failed at open", check(FakeCore(opens = false)))
    }

    @Test
    fun aCoreThatTellsNothingFailsAtTheFirstEventAwaited() {
        val silent = object : FakeCore() {
            override fun open(directory: String, key: ByteArray?, listener: CoreListener): CoreFailure? = null
        }
        assertEquals("core bridge: failed at list", check(silent))
    }

    @Test
    fun aRecorderThatHearsNothingFailsAtRecord() {
        val deaf = object : FakeCore() {
            override fun hear(track: Int, pcm: ByteArray) = Unit
        }
        assertEquals("core bridge: failed at record", check(deaf))
    }

    @Test
    fun aCoreThatLosesTheLinesFailsAtRead() {
        val forgetful = object : FakeCore() {
            override fun meeting(id: String) = CoreReply(text = null, CoreFailure.Unreadable)
        }
        assertEquals("core bridge: failed at read", check(forgetful))
    }

    @Test
    fun aCoreThatLosesTheSoundFailsAtSound() {
        val muted = object : FakeCore() {
            override fun sound(id: String, fromMs: Long, lengthMs: Long) = CoreSound(pcm = ByteArray(32_000), failure = null)
        }
        assertEquals("core bridge: failed at sound", check(muted))
    }

    @Test
    fun aCoreThatTakesAnyTextForARequestFailsThere() {
        val lenient = object : FakeCore() {
            override fun apply(request: String) = super.apply(request).takeUnless { it == CoreFailure.Invalid }
        }
        assertEquals("core bridge: failed at refused request", check(lenient))
    }

    @Test
    fun aCoreThatThrowsIsReportedNotRaised() {
        val broken = object : FakeCore() {
            override fun apply(request: String): CoreFailure? = error("bridge down")
        }
        assertEquals("core bridge: failed at exception", check(broken))
    }
}
