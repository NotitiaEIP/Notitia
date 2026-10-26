package com.example.notitia.ui.recording

import com.example.notitia.ui.meetings.twoDigits
import kotlin.math.PI
import kotlin.math.sin
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

// A made-up voice until the recorder gives the real volume: two waves out of step, taller in the middle.
fun waveLevel(index: Int, phase: Float): Float {
    val envelope = sin(PI * (index + 0.5) / WAVE_BARS).toFloat()
    val fast = 0.5f + 0.5f * sin(phase * 2.3f + index * 0.7f)
    val slow = 0.6f + 0.4f * sin(phase * 0.9f + index * 0.31f)
    return WAVE_REST_LEVEL + (1f - WAVE_REST_LEVEL) * envelope * fast * slow
}
