package com.example.notitia.bridge

import kotlin.test.Test
import kotlin.test.assertEquals

// The bridge of the phone, on the machine that builds: the Kotlin that UniFFI generates calls the core itself, built
// for this machine, and the self-check sends a made-up meeting through it and back. What the fake core of the other
// tests cannot prove: that the app and the core agree.
class RustCoreTest {

    @Test
    fun theCoreItselfAnswersThroughTheBridge() {
        assertEquals("core bridge: ok", CoreSelfCheck(RustCore()).run())
    }

    @Test
    fun aCoreThatIsNotOpenedSaysSoWithoutBeingCalled() {
        val closed = RustCore()
        assertEquals(CoreFailure.Broken, closed.meeting("recorded-1").failure)
        assertEquals(CoreFailure.Broken, closed.apply("{}"))
    }
}
