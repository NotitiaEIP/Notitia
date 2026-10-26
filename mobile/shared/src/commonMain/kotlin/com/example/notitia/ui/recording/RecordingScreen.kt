package com.example.notitia.ui.recording

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.example.notitia.ui.components.NotitiaBadge
import com.example.notitia.ui.meetings.clockTime
import com.example.notitia.ui.meetings.shortDateLabel
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.bookmark
import notitia.shared.generated.resources.edit
import notitia.shared.generated.resources.keyboard_arrow_down
import notitia.shared.generated.resources.lock
import notitia.shared.generated.resources.on_device
import notitia.shared.generated.resources.pause_filled
import notitia.shared.generated.resources.play_filled
import notitia.shared.generated.resources.recording_collapse
import notitia.shared.generated.resources.recording_in_progress
import notitia.shared.generated.resources.recording_mark
import notitia.shared.generated.resources.recording_pause
import notitia.shared.generated.resources.recording_paused
import notitia.shared.generated.resources.recording_resume
import notitia.shared.generated.resources.recording_stop
import notitia.shared.generated.resources.recording_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun RecordingScreen(
    startedAt: Instant,
    elapsed: Duration,
    paused: Boolean,
    onCollapse: () -> Unit,
    onMark: () -> Unit,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val spacing = NotitiaTheme.spacing
    Column(
        modifier = modifier.padding(horizontal = spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopBar(paused, onCollapse)
        Spacer(Modifier.weight(1f))
        MeetingTitle(startedAt)
        BasicText(
            text = timerText(elapsed),
            style = NotitiaTheme.typography.timer.copy(
                color = NotitiaTheme.colors.text,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            // At 200 % text, the hours would no longer fit on a small phone.
            autoSize = TextAutoSize.StepBased(maxFontSize = NotitiaTheme.typography.timer.fontSize),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s),
        )
        RecordingWave(paused = paused, moving = visible, modifier = Modifier.padding(top = spacing.xl))
        Spacer(Modifier.weight(1f))
        Controls(paused, onMark, onStop, onTogglePause, Modifier.padding(bottom = spacing.xl))
    }
}

@Composable
private fun TopBar(paused: Boolean, onCollapse: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse, modifier = Modifier.size(NotitiaTheme.sizes.touchTarget)) {
            Icon(
                painter = painterResource(Res.drawable.keyboard_arrow_down),
                contentDescription = stringResource(Res.string.recording_collapse),
                tint = NotitiaTheme.colors.text,
            )
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecordingDot(paused)
            Text(
                text = stringResource(if (paused) Res.string.recording_paused else Res.string.recording_in_progress),
                style = NotitiaTheme.typography.bodyStrong,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        NotitiaBadge(
            text = stringResource(Res.string.on_device),
            icon = painterResource(Res.drawable.lock),
        )
    }
}

@Composable
fun RecordingDot(paused: Boolean) {
    val colors = NotitiaTheme.colors
    val pulse = if (paused || reducedMotion()) {
        null
    } else {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                tween(NotitiaTheme.motion.PULSE_MS, easing = EaseInOut),
                RepeatMode.Reverse,
            ),
            label = "pulse",
        )
    }
    Box(
        Modifier
            .size(NotitiaTheme.sizes.recordingDot)
            .graphicsLayer { alpha = pulse?.value ?: 1f }
            .background(if (paused) colors.amber else colors.red, NotitiaTheme.shapes.full),
    )
}

@Composable
private fun MeetingTitle(startedAt: Instant) {
    val start = remember(startedAt) { startedAt.toLocalDateTime(TimeZone.currentSystemDefault()) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.recording_title, shortDateLabel(start.date), clockTime(start.time)),
            style = NotitiaTheme.typography.callout,
            color = NotitiaTheme.colors.textSecondary,
        )
        // Renaming comes with a later task: the pencil already says the title can change.
        Icon(
            painter = painterResource(Res.drawable.edit),
            contentDescription = null,
            tint = NotitiaTheme.colors.textSecondary,
            modifier = Modifier.size(NotitiaTheme.sizes.iconSmall),
        )
    }
}

@Composable
private fun Controls(
    paused: Boolean,
    onMark: () -> Unit,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NotitiaTheme.colors
    val sizes = NotitiaTheme.sizes
    // Bottom-aligned, so the three labels stay on one line although the stop button is bigger.
    // Each control gets a third of the width, so "Reprendre", longer than "Pause", moves nothing.
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        RecordingControl(
            label = stringResource(Res.string.recording_mark),
            onClick = onMark,
            modifier = Modifier.weight(1f),
            size = sizes.recordingControl,
            circle = Modifier.background(colors.surface),
        ) {
            ControlIcon(painterResource(Res.drawable.bookmark))
        }
        RecordingControl(
            label = stringResource(Res.string.recording_stop),
            onClick = onStop,
            modifier = Modifier.weight(1f),
            size = sizes.recordButton,
            circle = Modifier.border(sizes.stopOutline, colors.text, NotitiaTheme.shapes.full),
        ) {
            Box(Modifier.size(sizes.stopSquare).background(colors.red, NotitiaTheme.shapes.stopSquare))
        }
        RecordingControl(
            label = stringResource(if (paused) Res.string.recording_resume else Res.string.recording_pause),
            onClick = onTogglePause,
            modifier = Modifier.weight(1f),
            size = sizes.recordingControl,
            circle = Modifier.background(colors.surface),
        ) {
            ControlIcon(painterResource(if (paused) Res.drawable.play_filled else Res.drawable.pause_filled))
        }
    }
}

@Composable
private fun ControlIcon(painter: Painter) {
    Icon(
        painter = painter,
        contentDescription = null,
        tint = NotitiaTheme.colors.text,
        modifier = Modifier.size(NotitiaTheme.sizes.icon),
    )
}

// The circle and its label are one button: one touch target, read once by screen readers.
@Composable
private fun RecordingControl(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    size: Dp,
    circle: Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) NotitiaTheme.motion.PRESSED_SCALE else 1f,
        animationSpec = NotitiaTheme.motion.instant(),
        label = "pressed",
    )
    Column(
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Button,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .size(size)
                .clip(NotitiaTheme.shapes.full)
                .then(circle)
                .indication(interactionSource, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        Text(
            text = label,
            style = NotitiaTheme.typography.labelSmall,
            color = NotitiaTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@NotitiaPreviews
@Composable
private fun RecordingScreenPreview() = NotitiaTheme {
    RecordingScreen(
        startedAt = Clock.System.now(),
        elapsed = 12.minutes + 34.seconds,
        paused = false,
        onCollapse = {},
        onMark = {},
        onStop = {},
        onTogglePause = {},
        modifier = Modifier.background(NotitiaTheme.colors.surfaceRaised),
    )
}

@NotitiaPreviews
@Composable
private fun PausedRecordingScreenPreview() = NotitiaTheme {
    RecordingScreen(
        startedAt = Clock.System.now(),
        elapsed = 1.minutes * 62 + 3.seconds,
        paused = true,
        onCollapse = {},
        onMark = {},
        onStop = {},
        onTogglePause = {},
        modifier = Modifier.background(NotitiaTheme.colors.surfaceRaised),
    )
}
