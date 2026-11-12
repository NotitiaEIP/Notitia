package com.example.notitia.ui.recording

import com.example.notitia.ui.meetings.twoDigits
import kotlin.math.pow
import kotlin.time.Duration

const val WAVE_BARS = 34
const val WAVE_REST_LEVEL = 0.12f

fun timerText(elapsed: Duration): String {
    val total = elapsed.inWholeSeconds.coerceAtLeast(0)
    val hours = (total / 3600).toInt()
    val minutes = (total % 3600 / 60).toInt()
    val seconds = (total % 60).toInt()
    return if (hours > 0) "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
    else "${minutes.twoDigits()}:${seconds.twoDigits()}"
}

// A silent room still shows a short bar: the wave never disappears while it listens.
// Speech and shouting sit at the top of the decibel range: the curve keeps a voice near half height and lets a shout
// fill the bar.
private const val WAVE_CURVE = 1.6f

fun waveBarLevel(loudness: Float): Float =
    WAVE_REST_LEVEL + (1f - WAVE_REST_LEVEL) * loudness.coerceIn(0f, 1f).pow(WAVE_CURVE)
