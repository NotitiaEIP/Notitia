package com.example.notitia.recording

import kotlin.test.Test
import kotlin.test.assertEquals

private const val SPEECH_LEVEL = 3_000

// Alternating samples, so the level of the audio is exactly the given one.
internal fun audio(ms: Int, level: Int = SPEECH_LEVEL): ByteArray {
    val pcm = ByteArray(ms * BYTES_PER_MS)
    for (index in pcm.indices step 2) {
        val sample = if (index % 4 == 0) level else -level
        pcm[index] = sample.toByte()
        pcm[index + 1] = (sample shr 8).toByte()
    }
    return pcm
}

class PcmTest {
    @Test
    fun theLevelIsTheLoudnessOfTheSamples() {
        assertEquals(3_000.0, rms(audio(100)))
        assertEquals(0.0, rms(audio(100, level = 0)))
        assertEquals(0.0, rms(ByteArray(0)))
    }

    @Test
    fun theWavHeaderDescribesTheAudio() {
        val pcm = audio(1_000)
        val wav = pcmToWav(pcm)
        fun text(at: Int, length: Int) = wav.decodeToString(at, at + length)
        fun number(at: Int) = (0..3).sumOf { (wav[at + it].toInt() and 0xFF) shl (8 * it) }

        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", text(0, 4))
        assertEquals("WAVEfmt ", text(8, 8))
        assertEquals(36 + pcm.size, number(4))
        assertEquals(16_000, number(24))
        assertEquals(32_000, number(28))
        assertEquals("data", text(36, 4))
        assertEquals(pcm.size, number(40))
    }
}
