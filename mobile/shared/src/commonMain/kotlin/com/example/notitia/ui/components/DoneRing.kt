package com.example.notitia.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.person_done_description
import notitia.shared.generated.resources.person_done_ratio
import org.jetbrains.compose.resources.stringResource

private const val FULL_TURN = 360f
private const val FROM_NOON = -90f
private val RingCheck = CheckShape(Offset(8.4f, 12.3f), Offset(10.8f, 14.7f), Offset(15.6f, 9.9f))
private const val CHECK_STROKE = 2f

// "2/5" and a ring of what is done on a dim track of its own green, as Apple's activity rings: it fills from noon
// when it appears and follows each tick. Once everything is done the ring closes, a check traces itself inside and
// the phone gives a light tap, only when it closes under the user's finger, never when it appears already closed.
@Composable
fun DoneRing(done: Int, total: Int, modifier: Modifier = Modifier) {
    val colors = NotitiaTheme.colors
    val motion = NotitiaTheme.motion
    val reduced = reducedMotion()
    val complete = total > 0 && done == total
    val progress = remember { Animatable(0f) }
    val traced = remember { Animatable(if (complete) 1f else 0f) }
    LaunchedEffect(done, total) {
        progress.animateTo(if (total == 0) 0f else done.toFloat() / total, if (reduced) snap() else motion.arrive())
    }
    var closed by remember { mutableStateOf(complete) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(complete) {
        if (complete && !closed) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        closed = complete
        traced.animateTo(
            if (complete) 1f else 0f,
            if (reduced || !complete) snap() else tween(motion.STANDARD_MS, motion.STANDARD_MS, motion.easeOut),
        )
    }
    val description = stringResource(Res.string.person_done_description, done, total)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.person_done_ratio, done, total),
            style = NotitiaTheme.typography.caption,
            color = if (complete) colors.green else colors.textSecondary,
        )
        Canvas(Modifier.size(NotitiaTheme.sizes.doneRing)) {
            val stroke = NotitiaTheme.sizes.doneRingStroke.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawCircle(colors.ringTrack, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
            if (progress.value > 0f) {
                drawArc(
                    color = colors.green,
                    startAngle = FROM_NOON,
                    sweepAngle = FULL_TURN * progress.value,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            scale(size.minDimension / SYMBOL_GRID, pivot = Offset.Zero) {
                drawTracedCheck(RingCheck, colors.green, CHECK_STROKE, traced.value)
            }
        }
    }
}

@NotitiaPreviews
@Composable
private fun DoneRingPreview() = NotitiaTheme {
    Row(
        modifier = Modifier.background(NotitiaTheme.colors.surface).padding(NotitiaTheme.spacing.l),
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.l),
    ) {
        DoneRing(done = 0, total = 3)
        DoneRing(done = 2, total = 5)
        DoneRing(done = 4, total = 4)
    }
}
