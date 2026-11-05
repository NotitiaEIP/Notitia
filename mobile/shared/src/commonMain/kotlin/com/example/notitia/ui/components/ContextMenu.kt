package com.example.notitia.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// The actions of what was long pressed, under it on its end side, or over it when the screen ends too soon.
// Material's DropdownMenu always starts on the left of its anchor, hence this one. onClosed comes once the menu
// has faded out: an action that changes the list waits for it, so the menu never vanishes with its row.
@Composable
fun ContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onClosed: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    val closed by rememberUpdatedState(onClosed)
    LaunchedEffect(visibility.currentState, visibility.isIdle) {
        if (visibility.isIdle && !visibility.currentState) closed()
    }
    if (!visibility.currentState && !visibility.targetState) return

    val colors = NotitiaTheme.colors
    val motion = NotitiaTheme.motion
    val density = LocalDensity.current
    val reduced = reducedMotion()
    val gap = with(density) { NotitiaTheme.spacing.xs.roundToPx() }
    val bottomRoom = WindowInsets.navigationBars.getBottom(density) + with(density) { NotitiaTheme.spacing.s.roundToPx() }
    val origin = remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    val position = remember(gap, bottomRoom) { EndAlignedPosition(gap, bottomRoom) { origin.value = it } }

    val transition = rememberTransition(visibility, label = "menu")
    val scale by transition.animateFloat(
        transitionSpec = { if (reduced) snap() else motion.quick() },
        label = "scale",
    ) { shown -> if (shown || reduced) 1f else motion.ENTER_SCALE }
    val alpha by transition.animateFloat(
        transitionSpec = { if (reduced) snap() else motion.quick() },
        label = "alpha",
    ) { shown -> if (shown) 1f else 0f }

    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                transformOrigin = origin.value
            },
            shape = NotitiaTheme.shapes.card,
            color = colors.popup,
            contentColor = colors.text,
            // In dark, the lighter colour lifts it: no shadow, as everywhere else.
            shadowElevation = if (colors.isDark) NotitiaTheme.sizes.none else NotitiaTheme.sizes.raisedShadow,
        ) {
            Column(Modifier.width(IntrinsicSize.Max).padding(vertical = NotitiaTheme.spacing.xs), content = content)
        }
    }
}

@Composable
fun ContextMenuItem(icon: DrawableResource, text: String, color: Color, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = text, style = NotitiaTheme.typography.body, color = color) },
        leadingIcon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(NotitiaTheme.sizes.iconInButton),
            )
        },
        onClick = onClick,
    )
}

// End edges aligned with the anchor; below it if the menu fits above the navigation bar, else above it.
private class EndAlignedPosition(
    private val gap: Int,
    private val bottomRoom: Int,
    private val onOrigin: (TransformOrigin) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val ltr = layoutDirection == LayoutDirection.Ltr
        val x = if (ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val below = anchorBounds.bottom + gap
        val fitsBelow = below + popupContentSize.height <= windowSize.height - bottomRoom
        val y = if (fitsBelow) below else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        onOrigin(TransformOrigin(if (ltr) 1f else 0f, if (fitsBelow) 0f else 1f))
        return IntOffset(x, y)
    }
}
