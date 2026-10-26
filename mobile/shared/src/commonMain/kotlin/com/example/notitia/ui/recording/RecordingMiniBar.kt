package com.example.notitia.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.recording_in_progress
import notitia.shared.generated.resources.recording_paused
import notitia.shared.generated.resources.recording_stop_description
import org.jetbrains.compose.resources.stringResource

// The collapsed face of the recording sheet. The sheet draws the pill behind it and opens on a touch.
@Composable
fun RecordingMiniBar(recording: FakeRecording, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = NotitiaTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(NotitiaTheme.sizes.miniBar)
            .padding(start = spacing.xl, end = spacing.s),
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordingDot(recording.paused)
        Text(
            text = stringResource(
                if (recording.paused) Res.string.recording_paused else Res.string.recording_in_progress,
            ),
            style = NotitiaTheme.typography.bodyStrong,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = timerText(recording.elapsed),
            style = NotitiaTheme.typography.bodyStrong.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
        StopButton(onStop)
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    val colors = NotitiaTheme.colors
    val sizes = NotitiaTheme.sizes
    val description = stringResource(Res.string.recording_stop_description)
    Box(
        modifier = Modifier
            .size(sizes.touchTarget)
            .clip(NotitiaTheme.shapes.full)
            .clickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = sizes.buttonCompact / 2),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(sizes.buttonCompact).background(colors.redSoft, NotitiaTheme.shapes.full),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(sizes.iconSmall).background(colors.red, NotitiaTheme.shapes.stopSquareSmall))
        }
    }
}

@NotitiaPreviews
@Composable
private fun RecordingMiniBarPreview() = NotitiaTheme {
    Box(Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l)) {
        RecordingMiniBar(
            recording = FakeRecording(Clock.System.now(), elapsedSeconds = (12.minutes + 34.seconds).inWholeSeconds),
            onStop = {},
            modifier = Modifier.background(NotitiaTheme.colors.surfaceRaised, NotitiaTheme.shapes.full),
        )
    }
}
