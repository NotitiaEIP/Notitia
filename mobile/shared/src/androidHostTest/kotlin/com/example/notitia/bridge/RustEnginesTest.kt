package com.example.notitia.bridge

import java.io.File
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

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

    @Test
    fun anOfferTheCoreWouldHaveToCorrectIsRefused() {
        val core = RustCore()
        assertNull(core.open(folder.path, key = null) {})
        val withoutRemote = """[{"kind":"speech","name":"s","longestMs":30000}]"""
        for (wrong in listOf("", "{}", withoutRemote)) assertEquals(CoreFailure.Invalid, core.offer(wrong, none))
    }
}
