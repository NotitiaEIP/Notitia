package com.example.notitia.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import com.example.notitia.ui.theme.NotitiaTheme
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.launch
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.chat_bubble
import notitia.shared.generated.resources.check
import org.jetbrains.compose.resources.painterResource

// Swiping an action card to the right completes it, as in Google Tasks and Gmail: Material's own swipe follows
// the finger, takes the speed of a flick into account and sends the card off the screen. Swiping it to the left
// nudges its owner: the card comes back to its place while the message opens, as Gmail's snooze does. Without
// onNudge, it only goes right.
@Composable
fun SwipeableAction(
    enabled: Boolean,
    onDone: () -> Unit,
    onNudge: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = NotitiaTheme.motion
    // Not saved: a list restores the saved state of a key that comes back, and an undone action would come back
    // off the screen.
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled) { it * motion.SWIPE_THRESHOLD } }
    val armed = state.targetValue != SwipeToDismissBoxValue.Settled
    val haptics = LocalHapticFeedback.current
    val done by rememberUpdatedState(onDone)
    val nudge by rememberUpdatedState(onNudge)
    val scope = rememberCoroutineScope()
    // Once each time the threshold is crossed, as Android does for gestures.
    LaunchedEffect(armed) {
        if (armed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }
    SwipeToDismissBox(
        state = state,
        backgroundContent = { SwipeBackground(state, armed) },
        modifier = modifier,
        enableDismissFromEndToStart = onNudge != null,
        gesturesEnabled = enabled,
        // Material calls it again whenever it changes while the card is away: this one never changes.
        onDismiss = remember {
            { direction: SwipeToDismissBoxValue ->
                if (direction == SwipeToDismissBoxValue.EndToStart) {
                    nudge?.invoke()
                    scope.launch { state.reset() }
                } else {
                    done()
                }
            }
        },
    ) {
        content()
    }
}

// Coloured from the first millimetre, green to complete, violet to nudge: the colour says what the swipe does. The
// icon stays centred in the strip the card uncovers, and grows when letting go would act, as in Gmail.
@Composable
private fun SwipeBackground(state: SwipeToDismissBoxState, armed: Boolean) {
    val direction = state.dismissDirection
    if (direction == SwipeToDismissBoxValue.Settled) return
    val colors = NotitiaTheme.colors
    val nudging = direction == SwipeToDismissBoxValue.EndToStart
    val motion = NotitiaTheme.motion
    val icon = NotitiaTheme.sizes.icon
    val scale by animateFloatAsState(
        targetValue = if (armed) 1f else motion.SWIPE_ICON_REST_SCALE,
        animationSpec = motion.quick(),
        label = "swipe icon scale",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(NotitiaTheme.shapes.card)
            .background(if (nudging) colors.accent else colors.green),
        contentAlignment = if (nudging) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Icon(
            painter = painterResource(if (nudging) Res.drawable.chat_bubble else Res.drawable.check),
            contentDescription = null,
            tint = if (nudging) colors.onAccent else colors.onGreen,
            // The offset is read at placement, so following the card never recomposes. To the left, the offset is
            // negative and the strip starts from the end.
            modifier = Modifier
                .offset { IntOffset(((state.requireOffset() - icon.toPx() * sign(state.requireOffset())) / 2).roundToInt(), 0) }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}
