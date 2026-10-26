package com.example.notitia.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.minutes_share
import notitia.shared.generated.resources.share
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

// The main action of a screen: one per screen, at the bottom, full width.
@Composable
fun NotitiaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
) {
    val colors = NotitiaTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) NotitiaTheme.motion.PRESSED_SCALE else 1f,
        animationSpec = NotitiaTheme.motion.instant(),
        label = "pressed",
    )
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = NotitiaTheme.sizes.button)
            .clip(NotitiaTheme.shapes.full)
            .background(colors.accent)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = colors.onAccent),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = NotitiaTheme.spacing.xl, vertical = NotitiaTheme.spacing.s),
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(NotitiaTheme.sizes.iconInButton),
            )
        }
        Text(text = text, style = NotitiaTheme.typography.label, color = colors.onAccent)
    }
}

@NotitiaPreviews
@Composable
private fun NotitiaButtonPreview() = NotitiaTheme {
    Box(Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l)) {
        NotitiaButton(
            text = stringResource(Res.string.minutes_share),
            onClick = {},
            icon = painterResource(Res.drawable.share),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
