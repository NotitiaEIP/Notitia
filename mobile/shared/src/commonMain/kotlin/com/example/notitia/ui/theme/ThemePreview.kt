package com.example.notitia.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

// IDE preview only: the labels are token names, not interface text, so they stay out of the resources.
@Composable
private fun ThemeSample() {
    val colors = NotitiaTheme.colors
    val type = NotitiaTheme.typography
    Column(
        modifier = Modifier.background(colors.background).padding(NotitiaTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
    ) {
        Swatches(listOf(colors.surface, colors.surfaceRaised, colors.divider, colors.outline, colors.textTertiary))
        Swatches(listOf(colors.accent, colors.accentContent, colors.accentSoft, colors.red, colors.green, colors.amber))
        Swatches(colors.speakers)
        Box(
            Modifier.fillMaxWidth().height(NotitiaTheme.spacing.xl)
                .background(Brush.horizontalGradient(colors.buttonGradient), NotitiaTheme.shapes.full),
        )
        Text("display", style = type.display)
        Text("title1", style = type.title1)
        Text("title2", style = type.title2)
        Text("title3", style = type.title3)
        Text("body", style = type.body)
        Text("callout", style = type.callout, color = colors.textSecondary)
        Text("label", style = type.label, color = colors.accentContent)
        Text("caption", style = type.caption, color = colors.textSecondary)
        Text("12:45", style = type.timer)
        Text("00:12:31", style = type.timestamp, color = colors.textTertiary)
    }
}

@Composable
private fun Swatches(colors: List<Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s)) {
        colors.forEach { Box(Modifier.size(NotitiaTheme.sizes.touchTarget).background(it, NotitiaTheme.shapes.field)) }
    }
}

@Preview
@Composable
private fun ThemeLightPreview() = NotitiaTheme(darkTheme = false) { ThemeSample() }

@Preview
@Composable
private fun ThemeDarkPreview() = NotitiaTheme(darkTheme = true) { ThemeSample() }
