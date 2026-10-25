package com.example.notitia.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.example.notitia.ui.navigation.Tab
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme

// The values of the charter's demo, on the 24 × 24 grid of Material Symbols.
private const val GRID = 24f
private val CENTER = Offset(GRID / 2, GRID / 2)
private val IconStroke = 2f to 2.4f

private val BarLeft = listOf(2f, 6.4f, 10.8f, 15.2f, 19.6f)
private val BarHeight = listOf(7f, 14f, 18f, 11f, 7f)
private val BarStretch = listOf(
    listOf(1f, 1.9f, 0.6f, 1.5f, 0.8f, 1.15f, 1f),
    listOf(1f, 0.5f, 1.3f, 0.7f, 1.2f, 0.9f, 1f),
    listOf(1f, 0.6f, 1.2f, 0.5f, 1.1f, 0.95f, 1f),
    listOf(1f, 1.6f, 0.55f, 1.4f, 0.75f, 1.1f, 1f),
    listOf(1f, 1.2f, 1.9f, 0.7f, 1.4f, 0.9f, 1f),
).map { values -> values.mapIndexed { i, value -> i.toFloat() / (values.size - 1) to value } }
private const val BAR_WIDTH = 2.4f

// task_alt: the ring is open at the top right, from 64° round by the left to 5° (counted from the right, upwards).
private const val RING_RADIUS = 9f
private const val RING_TOP_END = 64f
private const val RING_SWEEP = 360f - (64f - 5f)
private val CheckStart = Offset(7.76f, 12.36f)
private val CheckCorner = Offset(10.59f, 15.18f)
private val CheckEnd = Offset(20.58f, 5.19f)
// (start, length) shares of the gesture, not a range.
private val RingTraceStep = 0f to 0.4f
private val CheckTraceStep = 0.3f to 0.4f
// The tip of the check crosses the ring around 60 % of the gesture: the icon swells just before and peaks
// just after, which makes it readable at 24 dp.
private val CheckSwell = listOf(0f to 1f, 0.55f to 1f, 0.72f to 1.18f, 0.88f to 0.96f, 1f to 1f)

private val LensCenter = Offset(10.5f, 10.5f)
private const val LENS_RADIUS = 6f
private val HandleStart = Offset(15f, 15f)
private val HandleEnd = Offset(20f, 20f)
private const val LENS_FILL_ALPHA = 0.2f
// A sweep rather than a full turn, which would look like loading.
private val LensTilt = listOf(0f to 0f, 0.25f to -18f, 0.55f to 14f, 0.8f to -6f, 1f to 0f)
private val LensShiftX = listOf(0f to 0f, 0.25f to -1f, 0.55f to 1f, 0.8f to 0f, 1f to 0f)
private val LensShiftY = listOf(0f to 0f, 0.25f to 0f, 0.55f to -1f, 0.8f to 0f, 1f to 0f)

private fun Pair<Float, Float>.at(fraction: Float) = first + (second - first) * fraction

private fun Pair<Float, Float>.progress(gesture: Float) = ((gesture - first) / second).coerceIn(0f, 1f)

private fun List<Pair<Float, Float>>.valueAt(gesture: Float): Float {
    val next = indexOfFirst { it.first >= gesture }.coerceAtLeast(1)
    val (fromTime, from) = this[next - 1]
    val (toTime, to) = this[next]
    return (from to to).at(EaseInOut.transform(((gesture - fromTime) / (toTime - fromTime)).coerceIn(0f, 1f)))
}

// Redrawn in code rather than taken from the font, so its strokes can move. weight and gesture are read at
// draw time: animating them redraws the icon without recomposing.
@Composable
internal fun TabIcon(tab: Tab, weight: () -> Float, gesture: () -> Float, color: Color, modifier: Modifier = Modifier) {
    val easeOut = NotitiaTheme.motion.easeOut
    Canvas(modifier.size(NotitiaTheme.sizes.icon)) {
        val w = weight()
        val t = gesture()
        scale(size.minDimension / GRID, pivot = Offset.Zero) {
            when (tab) {
                Tab.Meetings -> drawSoundBars(color, w, t)
                Tab.Actions -> drawCheck(
                    color = color,
                    weight = w,
                    ringTraced = easeOut.transform(RingTraceStep.progress(t)),
                    checkTraced = EaseInOut.transform(CheckTraceStep.progress(t)),
                    swell = CheckSwell.valueAt(t),
                )
                Tab.Search -> drawMagnifier(color, w, t)
            }
        }
    }
}

private fun DrawScope.drawSoundBars(color: Color, weight: Float, gesture: Float) {
    val width = IconStroke.at(weight)
    BarLeft.forEachIndexed { i, left ->
        val height = BarHeight[i] * BarStretch[i].valueAt(gesture)
        drawRoundRect(
            color = color,
            topLeft = Offset(left + (BAR_WIDTH - width) / 2, GRID / 2 - height / 2),
            size = Size(width, height),
            cornerRadius = CornerRadius(width / 2),
        )
    }
}

// Nothing is drawn before a stroke starts, or its round cap would show as a dot.
private fun DrawScope.drawCheck(color: Color, weight: Float, ringTraced: Float, checkTraced: Float, swell: Float) {
    val stroke = IconStroke.at(weight)
    scale(swell, pivot = CENTER) {
        if (ringTraced > 0f) {
            drawArc(
                color = color,
                startAngle = -RING_TOP_END,
                sweepAngle = -ringTraced * RING_SWEEP,
                useCenter = false,
                topLeft = CENTER - Offset(RING_RADIUS, RING_RADIUS),
                size = Size(RING_RADIUS * 2, RING_RADIUS * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        drawTracedCheck(color, stroke, checkTraced)
    }
}

private fun DrawScope.drawTracedCheck(color: Color, stroke: Float, traced: Float) {
    if (traced <= 0f) return
    val shortArm = (CheckCorner - CheckStart).getDistance()
    val longArm = (CheckEnd - CheckCorner).getDistance()
    val length = traced * (shortArm + longArm)
    val check = Path().apply {
        moveTo(CheckStart.x, CheckStart.y)
        val shortEnd = lerp(CheckStart, CheckCorner, (length / shortArm).coerceAtMost(1f))
        lineTo(shortEnd.x, shortEnd.y)
        if (length > shortArm) {
            val longEnd = lerp(CheckCorner, CheckEnd, (length - shortArm) / longArm)
            lineTo(longEnd.x, longEnd.y)
        }
    }
    // One path, so the corner is a round join.
    drawPath(check, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawMagnifier(color: Color, weight: Float, gesture: Float) {
    val stroke = IconStroke.at(weight)
    translate(left = LensShiftX.valueAt(gesture), top = LensShiftY.valueAt(gesture)) {
        rotate(LensTilt.valueAt(gesture), pivot = CENTER) {
            drawCircle(color, LENS_RADIUS, LensCenter, alpha = LENS_FILL_ALPHA * weight)
            drawCircle(color, LENS_RADIUS, LensCenter, style = Stroke(stroke))
            drawLine(color, HandleStart, HandleEnd, stroke, StrokeCap.Round)
        }
    }
}

@NotitiaPreviews
@Composable
private fun TabIconPreview() = NotitiaTheme {
    Column(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.l),
    ) {
        Tab.entries.forEach { tab ->
            Row(horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.l)) {
                TabIcon(tab, weight = { 0f }, gesture = { 1f }, color = NotitiaTheme.colors.textSecondary)
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { step ->
                    TabIcon(tab, weight = { 1f }, gesture = { step }, color = NotitiaTheme.colors.accentContent)
                }
            }
        }
    }
}
