package com.example.notitia.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

// Icons redrawn in code keep the 24 × 24 grid of Material Symbols, so their strokes match the font's.
internal const val SYMBOL_GRID = 24f
internal val SymbolCenter = Offset(SYMBOL_GRID / 2, SYMBOL_GRID / 2)

internal class CheckShape(val start: Offset, val corner: Offset, val end: Offset)

// traced runs from 0 to 1, from the short arm to the tip. One path, so the corner is a round join, and nothing
// before the stroke starts, or its round cap would show as a dot.
internal fun DrawScope.drawTracedCheck(shape: CheckShape, color: Color, stroke: Float, traced: Float) {
    if (traced <= 0f) return
    val shortArm = (shape.corner - shape.start).getDistance()
    val longArm = (shape.end - shape.corner).getDistance()
    val length = traced * (shortArm + longArm)
    val check = Path().apply {
        moveTo(shape.start.x, shape.start.y)
        val shortEnd = lerp(shape.start, shape.corner, (length / shortArm).coerceAtMost(1f))
        lineTo(shortEnd.x, shortEnd.y)
        if (length > shortArm) {
            val longEnd = lerp(shape.corner, shape.end, (length - shortArm) / longArm)
            lineTo(longEnd.x, longEnd.y)
        }
    }
    drawPath(check, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
