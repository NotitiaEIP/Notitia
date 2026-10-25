package com.example.notitia.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.mic_filled
import notitia.shared.generated.resources.record_meeting
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun RecordButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = NotitiaTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) NotitiaTheme.motion.PRESSED_SCALE else 1f,
        animationSpec = NotitiaTheme.motion.instant(),
        label = "pressed",
    )
    val gradient = remember(colors) { Brush.linearGradient(colors.buttonGradient) }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(NotitiaTheme.sizes.recordButton)
            .clip(NotitiaTheme.shapes.full)
            .background(gradient)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = colors.onAccent),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(Res.drawable.mic_filled),
            contentDescription = stringResource(Res.string.record_meeting),
            tint = colors.onAccent,
            modifier = Modifier.size(NotitiaTheme.sizes.recordIcon),
        )
    }
}

@NotitiaPreviews
@Composable
private fun RecordButtonPreview() = NotitiaTheme {
    Box(Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l)) {
        RecordButton(onClick = {})
    }
}
