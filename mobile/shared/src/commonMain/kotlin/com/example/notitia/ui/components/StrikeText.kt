package com.example.notitia.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion

// Struck, a line crosses the text out from left to right, line after line, like a pen on a list.
// TextDecoration.LineThrough cannot be animated, hence the line drawn over the text.
@Composable
fun StrikeText(
    text: String,
    struck: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val colors = NotitiaTheme.colors
    val motion = NotitiaTheme.motion
    val reduced = reducedMotion()
    val progress by animateFloatAsState(
        targetValue = if (struck) 1f else 0f,
        animationSpec = if (struck && !reduced) tween(motion.STRIKE_MS, easing = motion.easeOut) else snap(),
        label = "strike",
    )
    val color by animateColorAsState(
        targetValue = if (struck) colors.textTertiary else colors.text,
        animationSpec = if (reduced) snap() else motion.quick(),
        label = "color",
    )
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val thickness = NotitiaTheme.sizes.strikeLine
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            drawContent()
            layout?.let { drawStrike(it, progress, color, thickness.toPx()) }
        },
    )
}

private fun DrawScope.drawStrike(layout: TextLayoutResult, progress: Float, color: Color, thickness: Float) {
    if (progress <= 0f) return
    val widths = List(layout.lineCount) { layout.getLineRight(it) - layout.getLineLeft(it) }
    var remaining = progress * widths.sum()
    widths.forEachIndexed { line, width ->
        if (remaining <= 0f) return
        val start = layout.getLineLeft(line)
        val middle = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
        drawLine(color, Offset(start, middle), Offset(start + minOf(width, remaining), middle), thickness)
        remaining -= width
    }
}

@NotitiaPreviews
@Composable
private fun StrikeTextPreview() = NotitiaTheme {
    Column(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
    ) {
        StrikeText("Préparer la démo de la transcription", struck = false, style = NotitiaTheme.typography.body)
        StrikeText(
            "Tester un enregistrement d’une heure, écran verrouillé, sur trois téléphones",
            struck = true,
            style = NotitiaTheme.typography.body,
        )
    }
}
