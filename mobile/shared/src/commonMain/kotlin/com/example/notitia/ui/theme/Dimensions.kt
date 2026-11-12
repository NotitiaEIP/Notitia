package com.example.notitia.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
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
    // Wider than tall: the tick of an action sits at the edge of its card, where a thumb lands off target.
    val checkTarget = 56.dp
    val button = 52.dp
    val buttonCompact = 40.dp
    val field = 52.dp
    val gradientStroke = 2.dp
    val badge = 24.dp
    val citation = 20.dp
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
    val cardShadow = 1.dp
    val speakerAvatar = 22.dp
    val speakerDot = 8.dp
    val bullet = 6.dp
    val progressBar = 4.dp
    val tabBar = 64.dp
    val selectionBar = 64.dp
    val tabPillWidth = 64.dp
    val tabPillHeight = 32.dp
    val sidePanel = 320.dp
    val sheetRadius = 28.dp
    val strikeLine = 1.5.dp
    val quoteBar = 3.dp
    val personAvatar = 32.dp
    val doneRing = 28.dp
    val doneRingStroke = 3.dp
    // The faces of a meeting stand as tall as the ring of its actions, on the same line.
    val stackAvatar = doneRing
    val stackOverlap = 8.dp
    val stackGap = 2.dp
}

// What can be touched is round, what is read has corners.
object NotitiaShapes {
    val badge = RoundedCornerShape(8.dp)
    val field = RoundedCornerShape(12.dp)
    // Fixed corners rather than full: the bar grows to four lines and must not turn into an oval.
    val searchBar = RoundedCornerShape(NotitiaSizes.field / 2)
    val card = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = NotitiaSizes.sheetRadius, topEnd = NotitiaSizes.sheetRadius)
    val dialog = RoundedCornerShape(NotitiaSizes.sheetRadius)
    // The corner under the sender stays tight, as on a sent message.
    val bubble = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
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
    // The screen under an action's sheet steps back this much, as a card on a stack.
    const val STACK_SCALE = 0.94f
    // Long for a tab, but nobody waits for it: the screen has already switched.
    const val TAB_ICON_MS = 600
    // The recording dot breathes slowly: it says "still on", not "look at me".
    const val PULSE_MS = 1200
    // A quick answer never flashes a loader.
    const val LOADER_DELAY_MS = 300
    const val SCAN_STEP_MS = 90
    // A ticked action stays in its place, struck through, long enough to see it done or to take it back.
    const val ACTION_LINGER_MS = 1100
    const val CHECK_START_SCALE = 0.4f
    const val CHECK_TRACE_DELAY_MS = 80
    const val STRIKE_MS = 300
    // Share of the card's width past which letting go acts, as in Google Tasks and Gmail.
    const val SWIPE_THRESHOLD = 0.4f
    const val SWIPE_ICON_REST_SCALE = 0.8f
    // Material's fade through: the old content leaves in the first third, then the new one grows in.
    const val FADE_THROUGH_MS = 300
    const val FADE_THROUGH_OUT_MS = 105
    const val ARRIVE_MS = 400
    const val REVEAL_STAGGER_MS = 60
    val easeOut: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    private val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    // The curve of UIKit's cross-dissolve.
    private val uiKitEaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    fun <T> instant(): TweenSpec<T> = tween(INSTANT_MS, easing = easeOut)
    fun <T> quick(): TweenSpec<T> = tween(QUICK_MS, easing = easeOut)
    fun <T> standard(): TweenSpec<T> = tween(STANDARD_MS, easing = easeOut)
    fun <T> follow(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy)

    // Content that changes in place.
    fun swap(): ContentTransform = when (motionStyle) {
        MotionStyle.Material -> {
            val enter = tween<Float>(
                durationMillis = FADE_THROUGH_MS - FADE_THROUGH_OUT_MS,
                delayMillis = FADE_THROUGH_OUT_MS,
                easing = LinearOutSlowInEasing,
            )
            (fadeIn(enter) + scaleIn(enter, initialScale = ENTER_SCALE)) togetherWith
                fadeOut(tween(FADE_THROUGH_OUT_MS, easing = FastOutLinearInEasing))
        }
        MotionStyle.Cupertino ->
            fadeIn(tween(STANDARD_MS, easing = uiKitEaseInOut)) togetherWith
                fadeOut(tween(QUICK_MS, easing = uiKitEaseInOut))
    }

    // Something that arrives on screen.
    fun <T> arrive(): FiniteAnimationSpec<T> = when (motionStyle) {
        MotionStyle.Material -> tween(ARRIVE_MS, easing = emphasizedDecelerate)
        MotionStyle.Cupertino -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
    }
    // Softer than follow(): a whole screen travels, and it keeps the speed of the throw.
    fun <T> sheet(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}
