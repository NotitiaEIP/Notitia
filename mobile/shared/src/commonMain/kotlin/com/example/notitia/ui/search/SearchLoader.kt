package com.example.notitia.ui.search

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.search_looking
import notitia.shared.generated.resources.search_writing
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// The bars of the recording wave at rest, in fractions of its height.
private val restLevels = floatArrayOf(0.25f, 0.42f, 0.29f, 0.58f, 0.38f, 0.21f, 0.50f, 0.33f, 0.63f, 0.29f, 0.46f, 0.25f)
private val BARS = restLevels.size

// Twelve bars, then a pause of four before the light starts again from the left.
private const val SCAN_STEPS = 16
// Starts two bars before the first, so the light comes in rather than appears.
private const val LIGHT_START = -2f
private const val UNDER_LIGHT = 1.6f
private const val NEXT_TO_LIGHT = 1.25f
// While the answer is written, the bars of the passages found breathe between these two heights.
private const val FOUND_LOW = 1.25f
private const val FOUND_HIGH = 1.55f
private const val BREATH_REST = 0.5f
private const val REST_ALPHA = 0.3f
private const val NEAR_ALPHA = 0.45f

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

// The light reads the meetings like a playhead; then the moments found stay lit while the answer is written.
// No spinner and no AI icon.
@Composable
fun SearchLoader(meetings: Int, found: Int?, modifier: Modifier = Modifier) {
    val colors = NotitiaTheme.colors
    val sizes = NotitiaTheme.sizes
    val motion = NotitiaTheme.motion
    val reduced = reducedMotion()
    val loop = rememberInfiniteTransition(label = "loader")
    val light = loop.animateFloat(
        initialValue = LIGHT_START,
        targetValue = LIGHT_START + SCAN_STEPS,
        animationSpec = infiniteRepeatable(tween(motion.SCAN_STEP_MS * SCAN_STEPS, easing = LinearEasing)),
        label = "light",
    )
    // Slow, like the recording dot: still working, not frozen.
    val breath = loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(motion.PULSE_MS, easing = EaseInOut), RepeatMode.Reverse),
        label = "breath",
    )
    val writing = animateFloatAsState(
        targetValue = if (found == null) 0f else 1f,
        animationSpec = if (reduced) snap() else motion.standard(),
        label = "writing",
    )
    val lit = remember(found) { found?.let(::litBars).orEmpty() }
    // Previews run no effect: they show the loader at once.
    val inspecting = LocalInspectionMode.current
    var shown by remember { mutableStateOf(inspecting) }
    LaunchedEffect(Unit) {
        delay(motion.LOADER_DELAY_MS.milliseconds)
        shown = true
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, motion.quick(), label = "shown")

    val rest = colors.textTertiary.copy(alpha = REST_ALPHA)
    val near = colors.accentContent.copy(alpha = NEAR_ALPHA)
    Column(
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Decoration: the caption says what happens.
        Canvas(Modifier.size(width = sizes.waveBar * BARS + sizes.waveGap * (BARS - 1), height = sizes.waveHeight)) {
            val bar = sizes.waveBar.toPx()
            val step = bar + sizes.waveGap.toPx()
            // Read here only, so the light moves without recomposing the screen.
            val center = if (reduced) null else light.value
            val breathing = if (reduced) BREATH_REST else breath.value
            val mix = writing.value
            repeat(BARS) { index ->
                val distance = center?.let { abs(index - it) } ?: Float.POSITIVE_INFINITY
                val scanScale = when {
                    distance < 1f -> lerp(UNDER_LIGHT, NEXT_TO_LIGHT, distance)
                    distance < 2f -> lerp(NEXT_TO_LIGHT, 1f, distance - 1f)
                    else -> 1f
                }
                val scanColor = when {
                    distance < 1f -> lerp(colors.accentContent, near, distance)
                    distance < 2f -> lerp(near, rest, distance - 1f)
                    else -> rest
                }
                // Every other bar found breathes out while its neighbour breathes in.
                val order = lit.indexOf(index)
                val isFound = order >= 0
                val foundScale = lerp(FOUND_LOW, FOUND_HIGH, if (order % 2 == 0) breathing else 1f - breathing)
                val scale = lerp(scanScale, if (isFound) foundScale else 1f, mix)
                val color: Color = lerp(scanColor, if (isFound) colors.accentContent else rest, mix)
                val height = (restLevels[index] * scale).coerceAtMost(1f) * size.height
                drawRoundRect(
                    color = color,
                    topLeft = Offset(index * step, (size.height - height) / 2),
                    size = Size(bar, height),
                    cornerRadius = CornerRadius(bar / 2),
                )
            }
        }
        Text(
            text = if (found == null) {
                pluralStringResource(Res.plurals.search_looking, meetings, meetings)
            } else {
                stringResource(Res.string.search_writing)
            },
            style = NotitiaTheme.typography.callout,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = NotitiaTheme.spacing.m)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

// Spread over the row: one source lights the middle bar, two light a bar in each half.
private fun litBars(found: Int): List<Int> =
    (0 until found).map { ((it + 0.5f) * BARS / found).toInt() }.distinct()

@NotitiaPreviews
@Composable
private fun SearchLoaderPreview() = NotitiaTheme {
    Column(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.xl),
    ) {
        SearchLoader(meetings = 5, found = null)
        SearchLoader(meetings = 5, found = 2)
    }
}
