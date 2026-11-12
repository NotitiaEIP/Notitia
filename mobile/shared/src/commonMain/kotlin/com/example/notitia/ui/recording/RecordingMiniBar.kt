package com.example.notitia.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.notitia.recording.RecorderState
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.play_filled
import notitia.shared.generated.resources.recording_resume
import notitia.shared.generated.resources.recording_stop_description
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

// The collapsed face of the recording sheet. The sheet draws the pill behind it and opens on a touch. A recording on
// hold gets its Resume button here too, so nobody has to open the sheet to notice it stopped.
@Composable
fun RecordingMiniBar(
    recording: RecorderState.Active,
    onStop: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NotitiaTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(NotitiaTheme.sizes.miniBar)
            .padding(start = spacing.xl, end = spacing.s),
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordingDot(recording.held)
        Text(
            text = recordingStatus(recording),
            style = NotitiaTheme.typography.bodyStrong,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = timerText(recording.recorded),
            style = NotitiaTheme.typography.bodyStrong.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
        if (recording.held) {
            MiniBarButton(stringResource(Res.string.recording_resume), onResume, NotitiaTheme.colors.accentSoft) {
                Icon(
                    painter = painterResource(Res.drawable.play_filled),
                    contentDescription = null,
                    tint = NotitiaTheme.colors.accentContent,
                    modifier = Modifier.size(NotitiaTheme.sizes.iconSmall),
                )
            }
        }
        MiniBarButton(stringResource(Res.string.recording_stop_description), onStop, NotitiaTheme.colors.redSoft) {
            Box(
                Modifier
                    .size(NotitiaTheme.sizes.iconSmall)
                    .background(NotitiaTheme.colors.red, NotitiaTheme.shapes.stopSquareSmall),
            )
        }
    }
}

@Composable
private fun MiniBarButton(description: String, onClick: () -> Unit, background: Color, icon: @Composable () -> Unit) {
    val sizes = NotitiaTheme.sizes
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
            modifier = Modifier.size(sizes.buttonCompact).background(background, NotitiaTheme.shapes.full),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
    }
}

@NotitiaPreviews
@Composable
private fun RecordingMiniBarPreview() = NotitiaTheme {
    Column(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.l),
    ) {
        val startedAt = Clock.System.now()
        RecordingMiniBar(
            recording = RecorderState.Recording(startedAt, 12.minutes + 34.seconds, level = 0.5f),
            onStop = {},
            onResume = {},
            modifier = Modifier.background(NotitiaTheme.colors.surfaceRaised, NotitiaTheme.shapes.full),
        )
        RecordingMiniBar(
            recording = RecorderState.Interrupted(startedAt, 12.minutes + 34.seconds),
            onStop = {},
            onResume = {},
            modifier = Modifier.background(NotitiaTheme.colors.surfaceRaised, NotitiaTheme.shapes.full),
        )
    }
}
