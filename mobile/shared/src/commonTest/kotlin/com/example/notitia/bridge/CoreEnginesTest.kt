package com.example.notitia.bridge

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CoreEnginesTest {
    @Test
    fun anEngineThatThrowsAnswersThatItFailed() {
        val engines = neverThrowing { _, _, _ -> throw IllegalStateException("out of reach") }
        assertEquals(ENGINE_FAILED, engines.run("gateway-transcribe", "{}", ByteArray(2)))
    }

    @Test
    fun anEngineThatAnswersIsHeardAsItAnswered() {
        val asked = mutableListOf<Triple<String, String, ByteArray>>()
        val engines = neverThrowing { engine, request, sound ->
            asked += Triple(engine, request, sound)
            """{"type":"written","text":"a"}"""
        }
        val sound = byteArrayOf(1, 2)
        assertEquals("""{"type":"written","text":"a"}""", engines.run("gemini-nano", "{}", sound))
        assertEquals(listOf("gemini-nano" to "{}"), asked.map { it.first to it.second })
        assertContentEquals(sound, asked.single().third)
    }
}
