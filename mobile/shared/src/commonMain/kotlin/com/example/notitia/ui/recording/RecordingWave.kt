package com.example.notitia.ui.recording

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion

// The wave is decoration: the timer and the status say that the recording runs.
@Composable
fun RecordingWave(paused: Boolean, modifier: Modifier = Modifier, moving: Boolean = true) {
    val sizes = NotitiaTheme.sizes
    val gradient = NotitiaTheme.colors.logoGradient
    val animated = moving && !paused && !reducedMotion()
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        val start = withFrameNanos { it } - (phase * 1e9f).toLong()
        while (true) withFrameNanos { phase = (it - start) / 1e9f }
    }

    Canvas(
        modifier.size(
            width = sizes.waveBar * WAVE_BARS + sizes.waveGap * (WAVE_BARS - 1),
            height = sizes.waveHeight,
        ),
    ) {
        val bar = sizes.waveBar.toPx()
        val step = bar + sizes.waveGap.toPx()
        // One brush for all bars, so each takes the colour of the logo where it stands.
        val brush = Brush.horizontalGradient(gradient, endX = size.width)
        repeat(WAVE_BARS) { index ->
            val level = if (paused) WAVE_REST_LEVEL else waveLevel(index, phase)
            val height = (size.height * level).coerceAtLeast(bar)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(index * step, (size.height - height) / 2),
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
        RecordingWave(paused = false)
        RecordingWave(paused = true)
    }
}
