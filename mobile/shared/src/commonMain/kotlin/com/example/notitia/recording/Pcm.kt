package com.example.notitia.recording

import kotlin.math.sqrt

// What every microphone gives and every engine takes: 16 kHz mono 16-bit little-endian PCM.
const val SAMPLE_RATE = 16_000
const val BYTES_PER_SAMPLE = 2
const val BYTES_PER_MS = SAMPLE_RATE * BYTES_PER_SAMPLE / 1000

fun rms(pcm: ByteArray, from: Int = 0, to: Int = pcm.size): Double {
    val samples = (to - from) / BYTES_PER_SAMPLE
    if (samples == 0) return 0.0
    var sum = 0.0
    for (index in from until from + samples * BYTES_PER_SAMPLE step BYTES_PER_SAMPLE) {
        val sample = (pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xFF)
        sum += sample.toDouble() * sample
    }
    return sqrt(sum / samples)
}

private const val WAV_HEADER_BYTES = 44

// For an engine that takes a file, a gateway say. In memory only, for the time of a request: no WAV is ever stored
// (product rule 5).
fun pcmToWav(pcm: ByteArray): ByteArray {
    val wav = ByteArray(WAV_HEADER_BYTES + pcm.size)
    fun text(at: Int, value: String) = value.forEachIndexed { index, char -> wav[at + index] = char.code.toByte() }
    fun number(at: Int, value: Int, bytes: Int) = repeat(bytes) { wav[at + it] = (value shr (8 * it)).toByte() }

    text(0, "RIFF")
    number(4, WAV_HEADER_BYTES - 8 + pcm.size, bytes = 4)
    text(8, "WAVEfmt ")
    number(16, 16, bytes = 4)
    number(20, 1, bytes = 2)
    number(22, 1, bytes = 2)
    number(24, SAMPLE_RATE, bytes = 4)
    number(28, SAMPLE_RATE * BYTES_PER_SAMPLE, bytes = 4)
    number(32, BYTES_PER_SAMPLE, bytes = 2)
    number(34, BYTES_PER_SAMPLE * 8, bytes = 2)
    text(36, "data")
    number(40, pcm.size, bytes = 4)
    pcm.copyInto(wav, WAV_HEADER_BYTES)
    return wav
}
