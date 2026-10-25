package com.example.notitia.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LocalNotitiaColors = staticCompositionLocalOf { LightColors }
private val LocalNotitiaTypography = staticCompositionLocalOf<NotitiaTypography> {
    error("Wrap the screen in NotitiaTheme")
}

// Material 3 gets the same values, so the components we use as they are (sheets, dialogs, snackbars)
// look like Notitia too.
@Composable
fun NotitiaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val typography = notitiaTypography()
    val materialColors = remember(darkTheme) {
        colors.toMaterial(inverse = if (darkTheme) LightColors else DarkColors)
    }
    val materialTypography = remember(typography) { typography.toMaterial() }
    CompositionLocalProvider(
        LocalNotitiaColors provides colors,
        LocalNotitiaTypography provides typography,
    ) {
        MaterialTheme(colorScheme = materialColors, typography = materialTypography, shapes = MaterialShapes) {
            CompositionLocalProvider(LocalContentColor provides colors.text, content = content)
        }
    }
}

object NotitiaTheme {
    val colors: NotitiaColors
        @Composable @ReadOnlyComposable get() = LocalNotitiaColors.current
    val typography: NotitiaTypography
        @Composable @ReadOnlyComposable get() = LocalNotitiaTypography.current
    val spacing = NotitiaSpacing
    val sizes = NotitiaSizes
    val shapes = NotitiaShapes
    val motion = NotitiaMotion
}

// Every accent role is the violet, and surfaces get no violet tint when raised.
private fun NotitiaColors.toMaterial(inverse: NotitiaColors): ColorScheme =
    (if (isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accentContent,
        inversePrimary = inverse.accentContent,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentSoft,
        onSecondaryContainer = accentContent,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = accentSoft,
        onTertiaryContainer = accentContent,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = surface,
        onSurfaceVariant = textSecondary,
        surfaceTint = Color.Transparent,
        inverseSurface = text,
        inverseOnSurface = background,
        error = red,
        onError = onRed,
        errorContainer = redSoft,
        onErrorContainer = red,
        outline = outline,
        outlineVariant = divider,
        scrim = Color.Black,
        surfaceBright = surfaceRaised,
        surfaceDim = background,
        surfaceContainerLowest = background,
        surfaceContainerLow = surfaceRaised,
        surfaceContainer = surfaceRaised,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surface,
    )

private fun NotitiaTypography.toMaterial() = Typography(
    displayLarge = display,
    displayMedium = display,
    displaySmall = display,
    headlineLarge = display,
    headlineMedium = title1,
    headlineSmall = title2,
    titleLarge = title3,
    titleMedium = bodyStrong,
    titleSmall = labelSmall,
    bodyLarge = body,
    bodyMedium = callout,
    bodySmall = caption,
    labelLarge = label,
    labelMedium = tab,
    labelSmall = caption,
)

private val MaterialShapes = Shapes(
    extraSmall = NotitiaShapes.field,
    small = NotitiaShapes.full,
    medium = NotitiaShapes.card,
    large = NotitiaShapes.card,
    extraLarge = NotitiaShapes.dialog,
)
