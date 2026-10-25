package com.example.notitia.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class NotitiaColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val divider: Color,
    val outline: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContent: Color,
    val accentSoft: Color,
    val focus: Color,
    val red: Color,
    val onRed: Color,
    val redSoft: Color,
    val green: Color,
    val greenSoft: Color,
    val amber: Color,
    val speakers: List<Color>,
    val onSpeaker: Color,
    val logoGradient: List<Color>,
    val buttonGradient: List<Color>,
) {
    // index is the order in which the speaker first talked.
    fun speaker(index: Int): Color = speakers[index.mod(speakers.size)]
}

private val Violet = Color(0xFF882DCD)
private val LogoGradient = listOf(
    Color(0xFF1592D8), Color(0xFF4B3BC6), Color(0xFF8E2BB0), Color(0xFFE0207F), Color(0xFFF6475F),
)
// The dark end of the logo gradient, so white text on it stays readable.
private val ButtonGradient = listOf(Color(0xFF4B3BC6), Color(0xFF8E2BB0), Color(0xFFC21A7A))

internal val DarkColors = NotitiaColors(
    isDark = true,
    background = Color(0xFF0B0A0F),
    surface = Color(0xFF16151C),
    surfaceRaised = Color(0xFF201F28),
    divider = Color(0xFF2C2B36),
    outline = Color(0xFF6F6D7A),
    text = Color(0xFFF4F3F7),
    textSecondary = Color(0xFFA3A1AD),
    textTertiary = Color(0xFF8C8A97),
    accent = Violet,
    onAccent = Color.White,
    accentContent = Color(0xFFCB95F3),
    accentSoft = Violet.copy(alpha = 0.22f),
    focus = Color(0xFFB66EED),
    red = Color(0xFFFF5A52),
    onRed = Color(0xFF0B0A0F),
    redSoft = Color(0xFFFF5A52).copy(alpha = 0.16f),
    green = Color(0xFF3DD68C),
    greenSoft = Color(0xFF3DD68C).copy(alpha = 0.14f),
    amber = Color(0xFFFFB224),
    speakers = listOf(
        Color(0xFF5AA9FF), Color(0xFF2CC7B5), Color(0xFFF5B83D),
        Color(0xFFFF7AB6), Color(0xFF9BD84A), Color(0xFFFF8A4C),
    ),
    onSpeaker = Color(0xFF0B0A0F),
    logoGradient = LogoGradient,
    buttonGradient = ButtonGradient,
)

internal val LightColors = NotitiaColors(
    isDark = false,
    background = Color.White,
    surface = Color(0xFFF5F4F8),
    surfaceRaised = Color.White,
    divider = Color(0xFFE5E3EA),
    outline = Color(0xFF8D8B98),
    text = Color(0xFF121117),
    textSecondary = Color(0xFF5E5C69),
    textTertiary = Color(0xFF6F6D7A),
    accent = Violet,
    onAccent = Color.White,
    accentContent = Violet,
    accentSoft = Violet.copy(alpha = 0.10f),
    focus = Violet,
    red = Color(0xFFBF2D2A),
    onRed = Color.White,
    redSoft = Color(0xFFBF2D2A).copy(alpha = 0.10f),
    green = Color(0xFF15724A),
    greenSoft = Color(0xFF15724A).copy(alpha = 0.08f),
    amber = Color(0xFFA35200),
    speakers = listOf(
        Color(0xFF1F6FD1), Color(0xFF0E7C70), Color(0xFF9A6400),
        Color(0xFFC2266F), Color(0xFF4D7A12), Color(0xFFB4501A),
    ),
    onSpeaker = Color.White,
    logoGradient = LogoGradient,
    buttonGradient = ButtonGradient,
)
