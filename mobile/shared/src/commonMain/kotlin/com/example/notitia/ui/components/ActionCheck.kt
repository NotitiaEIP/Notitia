package com.example.notitia.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion

// radio_button_unchecked and check_circle, on the grid of Material Symbols.
private const val RING_RADIUS = 9f
private const val DISC_RADIUS = 10f
private const val STROKE = 2f
private val DiscCheck = CheckShape(Offset(7.9f, 12.2f), Offset(10.6f, 14.9f), Offset(16.1f, 9.4f))

// Ticked, the disc fills in green from 40 % of its size, then the check traces itself in it, a beat later, so
// the eye follows one then the other. Unticking is immediate. Callers give it its target and its meaning.
@Composable
fun ActionCheck(done: Boolean, modifier: Modifier = Modifier) =
    TickCircle(done, NotitiaTheme.colors.green, NotitiaTheme.colors.onGreen, modifier)

// The same circle in violet, which means chosen (DESIGN.md), as the circles of Mail and Gmail's selection.
@Composable
fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) =
    TickCircle(selected, NotitiaTheme.colors.accent, NotitiaTheme.colors.onAccent, modifier)

@Composable
private fun TickCircle(done: Boolean, disc: Color, mark: Color, modifier: Modifier) {
    val colors = NotitiaTheme.colors
    val motion = NotitiaTheme.motion
    val animate = done && !reducedMotion()
    val fill by animateFloatAsState(
        targetValue = if (done) 1f else 0f,
        animationSpec = if (animate) motion.quick() else snap(),
        label = "fill",
    )
    val traced by animateFloatAsState(
        targetValue = if (done) 1f else 0f,
        animationSpec = if (animate) tween(motion.STANDARD_MS, motion.CHECK_TRACE_DELAY_MS, motion.easeOut) else snap(),
        label = "check",
    )
    Canvas(modifier.size(NotitiaTheme.sizes.icon)) {
        scale(size.minDimension / SYMBOL_GRID, pivot = Offset.Zero) {
            if (fill < 1f) drawCircle(colors.textSecondary, RING_RADIUS, SymbolCenter, style = Stroke(STROKE))
            if (fill > 0f) {
                val grown = motion.CHECK_START_SCALE + (1f - motion.CHECK_START_SCALE) * fill
                drawCircle(disc, DISC_RADIUS * grown, SymbolCenter, alpha = fill)
            }
            drawTracedCheck(DiscCheck, mark, STROKE, traced)
        }
    }
}

@NotitiaPreviews
@Composable
private fun ActionCheckPreview() = NotitiaTheme {
    Row(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.l),
    ) {
        ActionCheck(done = false)
        ActionCheck(done = true)
        SelectionCheck(selected = true)
    }
}
