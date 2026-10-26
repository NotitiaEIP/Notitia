package com.example.notitia.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.schibsted_grotesk_bold
import notitia.shared.generated.resources.schibsted_grotesk_regular
import notitia.shared.generated.resources.schibsted_grotesk_semibold
import org.jetbrains.compose.resources.Font

@Immutable
data class NotitiaTypography(
    val display: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val callout: TextStyle,
    val label: TextStyle,
    val labelSmall: TextStyle,
    val caption: TextStyle,
    val badge: TextStyle,
    val tab: TextStyle,
    val timer: TextStyle,
    val timestamp: TextStyle,
)

@Composable
internal fun notitiaTypography(): NotitiaTypography {
    val family = FontFamily(
        Font(Res.font.schibsted_grotesk_regular, FontWeight.Normal),
        Font(Res.font.schibsted_grotesk_semibold, FontWeight.SemiBold),
        Font(Res.font.schibsted_grotesk_bold, FontWeight.Bold),
    )
    return remember(family) {
        NotitiaTypography(
            display = family.style(34, 40, FontWeight.Bold, letterSpacing = (-0.01).em),
            title1 = family.style(28, 34, FontWeight.Bold, letterSpacing = (-0.01).em),
            title2 = family.style(22, 28, FontWeight.SemiBold),
            title3 = family.style(18, 24, FontWeight.SemiBold),
            body = family.style(17, 26, FontWeight.Normal),
            bodyStrong = family.style(17, 26, FontWeight.SemiBold),
            callout = family.style(15, 22, FontWeight.Normal),
            label = family.style(16, 20, FontWeight.SemiBold),
            labelSmall = family.style(14, 18, FontWeight.SemiBold),
            caption = family.style(13, 18, FontWeight.Normal),
            badge = family.style(13, 16, FontWeight.SemiBold),
            tab = family.style(13, 16, FontWeight.SemiBold),
            timer = family.style(56, 60, FontWeight.SemiBold, tabularFigures = true),
            timestamp = family.style(13, 18, FontWeight.Normal, tabularFigures = true),
        )
    }
}

// Tabular figures keep every digit the same width, so a running timer does not shift.
private fun FontFamily.style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    tabularFigures: Boolean = false,
) = TextStyle(
    fontFamily = this,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = letterSpacing,
    fontFeatureSettings = if (tabularFigures) "tnum" else null,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)
