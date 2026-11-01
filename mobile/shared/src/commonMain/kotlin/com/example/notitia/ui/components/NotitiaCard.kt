package com.example.notitia.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.example.notitia.ui.meetings.sampleMeetings
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import kotlin.time.Clock

@Composable
fun NotitiaCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = NotitiaTheme.colors
    val shape = NotitiaTheme.shapes.card
    val shadow = NotitiaTheme.sizes.cardShadow
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) NotitiaTheme.motion.PRESSED_SCALE else 1f,
        animationSpec = NotitiaTheme.motion.instant(),
        label = "pressed",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.shape = shape
                clip = true
                // In dark a card is lighter than the background, which is enough to set it apart.
                shadowElevation = if (colors.isDark) 0f else shadow.toPx()
            }
            .background(colors.surface)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(NotitiaTheme.spacing.l),
        content = content,
    )
}

@NotitiaPreviews
@Composable
private fun NotitiaCardPreview() = NotitiaTheme {
    Column(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
    ) {
        sampleMeetings(Clock.System.now()).take(2).forEach { meeting ->
            NotitiaCard(onClick = {}) {
                Text(text = meeting.title, style = NotitiaTheme.typography.title3)
            }
        }
    }
}
