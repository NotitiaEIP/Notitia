package com.example.notitia.ui.recording

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import com.example.notitia.recording.RecorderState
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

// About one read of the microphone: each new bar has slid into place when the next one comes.
private const val SLIDE_MS = 100

// The last levels heard, newest on the right, like the recorders of the phone. A pause freezes the wave where it was.
// The wave is decoration: the timer and the status say that the recording runs.
@Composable
fun RecordingWave(recording: RecorderState.Active, modifier: Modifier = Modifier, moving: Boolean = true) {
    val sizes = NotitiaTheme.sizes
    val gradient = NotitiaTheme.colors.logoGradient
    val levels = remember { mutableStateListOf<Float>().apply { repeat(WAVE_BARS) { add(WAVE_REST_LEVEL) } } }
    val slide = remember { Animatable(0f) }
    val sliding = moving && !reducedMotion()
    LaunchedEffect(recording.recorded) {
        if (recording !is RecorderState.Recording) return@LaunchedEffect
        levels.removeAt(0)
        levels.add(waveBarLevel(recording.level))
        if (sliding) {
            slide.snapTo(1f)
            slide.animateTo(0f, tween(SLIDE_MS, easing = LinearEasing))
        }
    }

    Canvas(
        modifier
            .size(width = sizes.waveBar * WAVE_BARS + sizes.waveGap * (WAVE_BARS - 1), height = sizes.waveHeight)
            .clipToBounds(),
    ) {
        val bar = sizes.waveBar.toPx()
        val step = bar + sizes.waveGap.toPx()
        // One brush for all bars, so each takes the colour of the logo where it stands.
        val brush = Brush.horizontalGradient(gradient, endX = size.width)
        val shift = slide.value * step
        levels.forEachIndexed { index, level ->
            val height = (size.height * level).coerceAtLeast(bar)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(index * step + shift, (size.height - height) / 2),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

@NotitiaPreviews
@Composable
private fun RecordingWavePreview() = NotitiaTheme {
    Column(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.l),
    ) {
        RecordingWave(RecorderState.Recording(Clock.System.now(), 1.minutes, level = 0.6f))
        RecordingWave(RecorderState.Paused(Clock.System.now(), 1.minutes))
    }
}
