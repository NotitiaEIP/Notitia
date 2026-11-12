package com.example.notitia.recording

import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

// What every microphone gives and every engine takes: 16 kHz mono 16-bit little-endian PCM.
const val SAMPLE_RATE = 16_000
const val BYTES_PER_SAMPLE = 2
const val BYTES_PER_MS = SAMPLE_RATE * BYTES_PER_SAMPLE / 1000

// From microseconds, so many small reads add up without rounding away a few milliseconds each.
fun pcmDuration(bytes: Int): Duration = (bytes.toLong() * 1000 / BYTES_PER_MS).microseconds

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

// The ear hears decibels, not amplitude: -50 dBFS (a quiet room) gives 0, -6 dBFS (a shout close to the phone) gives 1.
private const val FLOOR_DB = -50.0
private const val LOUD_DB = -6.0
private const val FULL_SCALE = 32_768.0

// The loudest syllable of a read, not its average: a word stands out from the short pauses around it.
private const val PEAK_WINDOW_BYTES = 20 * BYTES_PER_MS

fun loudness(pcm: ByteArray): Float {
    val level = (pcm.indices step PEAK_WINDOW_BYTES).maxOfOrNull { rms(pcm, it, minOf(it + PEAK_WINDOW_BYTES, pcm.size)) }
    if (level == null || level <= 0.0) return 0f
    val db = 20 * log10(level / FULL_SCALE)
    return ((db - FLOOR_DB) / (LOUD_DB - FLOOR_DB)).toFloat().coerceIn(0f, 1f)
}
