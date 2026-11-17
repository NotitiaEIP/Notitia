package com.example.notitia.bridge

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreSelfCheckTest {

    @Test
    fun aFaithfulCorePasses() {
        assertEquals("core bridge: ok", CoreSelfCheck(FakeCore()).run())
    }

    @Test
    fun aFolderThatCannotBeOpenedFailsAtOpen() {
        assertEquals("core bridge: failed at open", CoreSelfCheck(FakeCore(opens = false)).run())
    }

    @Test
    fun aCoreThatTellsNothingFailsAtTheFirstEventAwaited() {
        val silent = object : FakeCore() {
            override fun open(directory: String, listener: CoreListener): CoreFailure? = null
        }
        assertEquals("core bridge: failed at list", CoreSelfCheck(silent).run())
    }

    @Test
    fun aCoreThatLosesTheLinesFailsAtRead() {
        val forgetful = object : FakeCore() {
            override fun meeting(id: String) = CoreReply(text = null, CoreFailure.Unreadable)
        }
        assertEquals("core bridge: failed at read", CoreSelfCheck(forgetful).run())
    }

    @Test
    fun aCoreThatTakesAnyTextForAChangeFailsThere() {
        val lenient = object : FakeCore() {
            override fun apply(change: String) = super.apply(change).takeUnless { it == CoreFailure.Invalid }
        }
        assertEquals("core bridge: failed at refused change", CoreSelfCheck(lenient).run())
    }

    @Test
    fun aCoreThatThrowsIsReportedNotRaised() {
        val broken = object : FakeCore() {
            override fun apply(change: String): CoreFailure? = error("bridge down")
        }
        assertEquals("core bridge: failed at exception", CoreSelfCheck(broken).run())
    }
}
