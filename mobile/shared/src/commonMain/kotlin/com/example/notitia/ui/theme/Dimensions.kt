package com.example.notitia.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

object NotitiaSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

object NotitiaSizes {
    val touchTarget = 48.dp
    val button = 52.dp
    val buttonCompact = 40.dp
    val field = 52.dp
    val badge = 24.dp
    val icon = 24.dp
    val iconSmall = 16.dp
    val iconInButton = 20.dp
    val recordButton = 72.dp
    val recordIcon = 32.dp
    val recordingControl = 56.dp
    val recordingDot = 10.dp
    val stopSquare = 28.dp
    val stopOutline = 3.dp
    val waveBar = 4.dp
    val waveGap = 3.dp
    val waveHeight = 48.dp
    val none = 0.dp
    val miniBar = 64.dp
    val handleWidth = 32.dp
    val handleHeight = 4.dp
    val raisedShadow = 6.dp
    val progressBar = 4.dp
    val tabBar = 64.dp
    val tabPillWidth = 64.dp
    val tabPillHeight = 32.dp
}

// What can be touched is round, what is read has corners.
object NotitiaShapes {
    val badge = RoundedCornerShape(8.dp)
    val field = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val dialog = RoundedCornerShape(28.dp)
    val full = RoundedCornerShape(percent = 50)
    val stopSquare = RoundedCornerShape(7.dp)
    val stopSquareSmall = RoundedCornerShape(4.dp)
}

// Things done a hundred times a day stay short, and nothing bounces unless it was thrown.
object NotitiaMotion {
    const val INSTANT_MS = 100
    const val QUICK_MS = 150
    const val STANDARD_MS = 250
    const val PRESSED_SCALE = 0.97f
    const val ENTER_SCALE = 0.95f
    // Long for a tab, but nobody waits for it: the screen has already switched.
    const val TAB_ICON_MS = 800
    // The recording dot breathes slowly: it says "still on", not "look at me".
    const val PULSE_MS = 1200
    val easeOut: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    fun <T> instant(): TweenSpec<T> = tween(INSTANT_MS, easing = easeOut)
    fun <T> quick(): TweenSpec<T> = tween(QUICK_MS, easing = easeOut)
    fun <T> standard(): TweenSpec<T> = tween(STANDARD_MS, easing = easeOut)
    fun <T> follow(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy)
    // Softer than follow(): a whole screen travels, and it keeps the speed of the throw.
    fun <T> sheet(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}
