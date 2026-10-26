package com.example.notitia.ui.recording

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.recording_collapse
import notitia.shared.generated.resources.recording_expand
import notitia.shared.generated.resources.recording_handle
import org.jetbrains.compose.resources.stringResource

enum class PlayerPosition { Collapsed, Expanded }

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

// The mini bar fades out over the first part of the gesture, the full screen fades in over the rest,
// so the two faces never show at once.
private const val MINI_FADE_END = 0.3f
private const val FULL_FADE_START = 0.15f

@Stable
class RecordingPlayerState(initialValue: PlayerPosition, openOnStart: Boolean) {
    internal val draggable = AnchoredDraggableState(initialValue)
    internal var collapsedOffset by mutableFloatStateOf(Float.NaN)
        private set

    // A request waits for the anchors: the sheet cannot move before it knows where it goes.
    internal var request by mutableStateOf(if (openOnStart) PlayerPosition.Expanded else null)
        private set

    // 0 for the mini bar, 1 for the full screen. Read it only while placing or drawing, so that a drag
    // never recomposes the screen.
    internal val progress: Float
        get() {
            val offset = draggable.offset
            val collapsed = collapsedOffset
            if (offset.isNaN() || collapsed.isNaN() || collapsed <= 0f) {
                return if (draggable.currentValue == PlayerPosition.Expanded) 1f else 0f
            }
            return (1f - offset / collapsed).coerceIn(0f, 1f)
        }

    // The snap spring may overshoot an anchor slightly: the sheet never leaves its two positions.
    internal val visibleOffset: Float
        get() = lerp(collapsedOffset.takeUnless { it.isNaN() } ?: 0f, 0f, progress)

    fun expand() {
        request = PlayerPosition.Expanded
    }

    fun collapse() {
        request = PlayerPosition.Collapsed
    }

    internal fun clearRequest() {
        request = null
    }

    internal fun updateAnchors(collapsed: Float) {
        if (collapsed == collapsedOffset) return
        collapsedOffset = collapsed
        draggable.updateAnchors(
            DraggableAnchors {
                PlayerPosition.Expanded at 0f
                PlayerPosition.Collapsed at collapsed
            },
        )
    }

    companion object {
        val Saver: Saver<RecordingPlayerState, String> = Saver(
            save = { it.draggable.currentValue.name },
            restore = { RecordingPlayerState(PlayerPosition.valueOf(it), openOnStart = false) },
        )
    }
}

// Stacks the tabs, the recording sheet and the tab bar, in that order: the collapsed sheet sits above the
// tab bar, and the tab bar slides away as the sheet opens over it.
@Composable
fun RecordingPlayerHost(
    recording: FakeRecording?,
    state: RecordingPlayerState,
    onStop: () -> Unit,
    tabBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val sizes = NotitiaTheme.sizes
    val gap = NotitiaTheme.spacing.s
    var tabBarHeight by remember { mutableIntStateOf(0) }

    BoxWithConstraints(Modifier.fillMaxSize().background(NotitiaTheme.colors.background)) {
        val tabBarDp = with(density) { tabBarHeight.toDp() }
        val top = WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
        val miniBarRoom = if (recording != null) sizes.miniBar + gap * 2 else 0.dp
        content(PaddingValues(top = top, bottom = tabBarDp + miniBarRoom))

        if (recording != null) {
            val collapsedOffset = with(density) {
                constraints.maxHeight - tabBarHeight - (gap + sizes.miniBar).toPx()
            }
            SideEffect { state.updateAnchors(collapsedOffset) }
            RecordingPlayer(
                recording = recording,
                state = state,
                fullWidth = constraints.maxWidth,
                fullHeight = constraints.maxHeight,
                onStop = onStop,
            )
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { tabBarHeight = it.height }
                .graphicsLayer { translationY = (if (recording != null) state.progress else 0f) * size.height },
        ) {
            tabBar()
        }
    }
}

@Composable
private fun RecordingPlayer(
    recording: FakeRecording,
    state: RecordingPlayerState,
    fullWidth: Int,
    fullHeight: Int,
    onStop: () -> Unit,
) {
    val colors = NotitiaTheme.colors
    val sizes = NotitiaTheme.sizes
    val density = LocalDensity.current
    val sideMargin = with(density) { NotitiaTheme.spacing.l.toPx() }
    val miniBarHeight = with(density) { sizes.miniBar.toPx() }
    val shadow = with(density) { if (colors.isDark) 0f else sizes.raisedShadow.toPx() }
    val reduced = reducedMotion()
    val spec: AnimationSpec<Float> = if (reduced) snap() else NotitiaTheme.motion.sheet()
    // Flips once per gesture, at half way: the only moment the sheet recomposes while it moves.
    val expanded by remember(state) { derivedStateOf { state.progress > 0.5f } }

    LaunchedEffect(state.request) {
        val target = state.request ?: return@LaunchedEffect
        // Keyed on the request alone: new anchors (a rotation, the tab bar measured) must not cancel the move.
        snapshotFlow { state.collapsedOffset }.first { !it.isNaN() }
        try {
            state.draggable.animateTo(target, spec)
        } finally {
            state.clearRequest()
        }
    }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = expanded,
        onBackCompleted = state::collapse,
    )

    Box(
        Modifier
            .offset {
                // Below the screen until the anchors are known, so the sheet never flashes at the top.
                val top = if (state.collapsedOffset.isNaN()) fullHeight else state.visibleOffset.roundToInt()
                IntOffset(lerp(sideMargin, 0f, state.progress).roundToInt(), top)
            }
            // The pill grows from the mini bar to the whole screen. Only this box is measured again on
            // each frame: what it holds keeps its full size and is only uncovered.
            .layout { measurable, _ ->
                val progress = state.progress
                val width = lerp(fullWidth - 2 * sideMargin, fullWidth.toFloat(), progress).roundToInt()
                val height = lerp(miniBarHeight, fullHeight.toFloat(), progress).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .anchoredDraggable(
                state = state.draggable,
                reverseDirection = false,
                orientation = Orientation.Vertical,
                flingBehavior = AnchoredDraggableDefaults.flingBehavior(state.draggable, animationSpec = spec),
            )
            .graphicsLayer {
                val progress = state.progress
                this.shape = RoundedCornerShape(lerp(miniBarHeight / 2, 0f, progress))
                clip = true
                shadowElevation = shadow * (1f - progress)
            }
            .background(colors.surfaceRaised),
    ) {
        FullScreen(
            recording = recording,
            state = state,
            visible = expanded,
            onStop = onStop,
            modifier = Modifier
                .layout { measurable, constraints ->
                    // Always the size of the screen, so the content is laid out once, not on every frame.
                    val placeable = measurable.measure(Constraints.fixed(fullWidth, fullHeight))
                    val left = -lerp(sideMargin, 0f, state.progress).roundToInt()
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(left, 0) }
                }
                .graphicsLayer {
                    alpha = ((state.progress - FULL_FADE_START) / (1f - FULL_FADE_START)).coerceIn(0f, 1f)
                },
        )
        if (!expanded) {
            val expandLabel = stringResource(Res.string.recording_expand)
            RecordingMiniBar(
                recording = recording,
                onStop = onStop,
                modifier = Modifier
                    .graphicsLayer { alpha = (1f - state.progress / MINI_FADE_END).coerceIn(0f, 1f) }
                    .clickable(onClickLabel = expandLabel, role = Role.Button, onClick = state::expand),
            )
        }
    }
}

@Composable
private fun FullScreen(
    recording: FakeRecording,
    state: RecordingPlayerState,
    visible: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hidden from screen readers while it is folded away under the mini bar.
    Column(if (visible) modifier else modifier.clearAndSetSemantics {}) {
        Handle(state, Modifier.statusBarsPadding())
        RecordingScreen(
            startedAt = recording.startedAt,
            elapsed = recording.elapsed,
            paused = recording.paused,
            onCollapse = state::collapse,
            onMark = {},
            onStop = onStop,
            onTogglePause = recording::togglePause,
            visible = visible,
            modifier = Modifier.weight(1f).navigationBarsPadding(),
        )
    }
}

// Dragging works anywhere on the sheet; the handle says so, and gives screen readers the same two moves.
@Composable
private fun Handle(state: RecordingPlayerState, modifier: Modifier = Modifier) {
    val sizes = NotitiaTheme.sizes
    val description = stringResource(Res.string.recording_handle)
    val collapseLabel = stringResource(Res.string.recording_collapse)
    val expandLabel = stringResource(Res.string.recording_expand)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(sizes.touchTarget / 2)
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick(label = collapseLabel) {
                    state.collapse()
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(expandLabel) {
                        state.expand()
                        true
                    },
                    CustomAccessibilityAction(collapseLabel) {
                        state.collapse()
                        true
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = sizes.handleWidth, height = sizes.handleHeight)
                .background(NotitiaTheme.colors.outline, NotitiaTheme.shapes.full),
        )
    }
}
