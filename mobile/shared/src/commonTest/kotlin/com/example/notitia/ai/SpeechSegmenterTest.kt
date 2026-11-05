package com.example.notitia.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val BYTES_PER_MS = 32
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

class SpeechSegmenterTest {

    @Test
    fun silenceGivesNothing() {
        val segmenter = SpeechSegmenter()
        assertTrue(segmenter.add(audio(20_000, level = 0)).isEmpty())
        assertNull(segmenter.flush())
    }

    @Test
    fun aPauseCutsTheSpeech() {
        val segments = SpeechSegmenter().add(audio(3_000) + audio(800, level = 0))
        val segment = segments.single()
        assertEquals(0.seconds, segment.start)
        assertTrue(segment.length in 3_500.milliseconds..3_800.milliseconds, "length: ${segment.length}")
    }

    @Test
    fun aShortBreathDoesNotCutTheSpeech() {
        assertTrue(SpeechSegmenter().add(audio(3_000) + audio(300, level = 0) + audio(1_000)).isEmpty())
    }

    @Test
    fun endlessSpeechIsCutAtTheLongestLength() {
        val segmenter = SpeechSegmenter()
        val first = segmenter.add(audio(20_000)).single()
        assertEquals(15.seconds, first.length)

        val last = assertNotNull(segmenter.flush())
        assertEquals(15.seconds, last.start)
        assertEquals(5.seconds, last.length)
    }

    @Test
    fun silenceDroppedStillCountsInTheTime() {
        val segmenter = SpeechSegmenter()
        assertTrue(segmenter.add(audio(3_000, level = 0)).isEmpty())
        segmenter.add(audio(3_000))
        val segment = assertNotNull(segmenter.flush())
        assertTrue(segment.start in 2_500.milliseconds..3_000.milliseconds, "start: ${segment.start}")
    }

    @Test
    fun theSizeOfTheChunksChangesNothing() {
        val pcm = audio(3_000) + audio(800, level = 0)
        val whole = SpeechSegmenter().add(pcm).single()
        val segmenter = SpeechSegmenter()
        val pieces = pcm.toList().chunked(7).flatMap { segmenter.add(it.toByteArray()) }
        assertTrue(whole.pcm.contentEquals(pieces.single().pcm))
    }

    @Test
    fun theNoiseOfAQuietRoomDoesNotCountAsSpeech() {
        val segmenter = SpeechSegmenter()
        assertTrue(segmenter.add(audio(3_000, level = 250)).isEmpty())
        assertNull(segmenter.flush())
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
