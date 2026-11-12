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

// The ear hears decibels, not amplitude: -50 dBFS (a quiet room) shows flat, 0 dBFS fills the bar.
private const val FLOOR_DB = -50.0
private const val FULL_SCALE = 32_768.0

fun loudness(pcm: ByteArray): Float {
    val level = rms(pcm)
    if (level <= 0.0) return 0f
    val db = 20 * log10(level / FULL_SCALE)
    return ((db - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0f, 1f)
}
