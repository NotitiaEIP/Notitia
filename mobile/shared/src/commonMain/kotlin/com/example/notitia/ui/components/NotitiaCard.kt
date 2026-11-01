package com.example.notitia.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.example.notitia.ui.meetings.sampleMeetings
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import kotlin.time.Clock
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.chevron_right
import org.jetbrains.compose.resources.painterResource

@Composable
fun NotitiaCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
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
            }
            .cardShadow(NotitiaTheme.shapes.card)
            .background(NotitiaTheme.colors.surface)
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

@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier) {
        Text(
            text = text,
            style = NotitiaTheme.typography.title3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = NotitiaTheme.spacing.s),
        )
        CardChevron()
    }
}

// Says that the whole card opens something.
@Composable
fun CardChevron(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(Res.drawable.chevron_right),
        contentDescription = null,
        tint = NotitiaTheme.colors.textTertiary,
        modifier = modifier.size(NotitiaTheme.sizes.icon),
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
            NotitiaCard(onClick = {}) { CardTitle(meeting.title) }
        }
    }
}
