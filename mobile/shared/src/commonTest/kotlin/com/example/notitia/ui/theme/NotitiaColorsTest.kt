package com.example.notitia.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotitiaColorsTest {

    // WCAG AA: 4.5:1 for text, 3:1 for field outlines.
    @Test
    fun paletteMeetsAaContrastInBothThemes() {
        for (c in listOf(LightColors, DarkColors)) {
            val texts = listOf(c.text, c.textSecondary, c.textTertiary, c.accentContent, c.red, c.green, c.amber) + c.speakers
            texts.forEach { assertContrast(it, c.background, 4.5f) }
            assertContrast(c.accentContent, c.accentSoft.compositeOver(c.background), 4.5f)
            assertContrast(c.onAccent, c.accent, 4.5f)
            assertContrast(c.onRed, c.red, 4.5f)
            assertContrast(c.outline, c.background, 3f)
            c.buttonGradient.forEach { assertContrast(Color.White, it, 4.5f) }
        }
    }

    @Test
    fun speakerColorsStartAgainAfterSix() {
        assertEquals(DarkColors.speaker(0), DarkColors.speaker(6))
        assertEquals(LightColors.speaker(5), LightColors.speaker(11))
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        val ratio = (lighter + 0.05f) / (darker + 0.05f)
        assertTrue(ratio >= minimum, "$foreground on $background: $ratio, expected $minimum")
    }
}
