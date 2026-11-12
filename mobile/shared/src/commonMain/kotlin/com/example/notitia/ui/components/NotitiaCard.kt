package com.example.notitia.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import com.example.notitia.ui.meetings.sampleItems
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import kotlin.time.Clock
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.chevron_right
import org.jetbrains.compose.resources.painterResource

// onLongClick opens the card's context menu, or starts choosing; longClickLabel says so to screen readers. A chosen
// card takes the accent's soft tint, as a selected row in Gmail or Mail.
@Composable
fun NotitiaCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = NotitiaTheme.colors
    val background by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else colors.surface,
        animationSpec = NotitiaTheme.motion.quick(),
        label = "selected",
    )
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
            .background(background)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Button,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(NotitiaTheme.spacing.l),
        content = content,
    )
}

// Without the chevron while choosing: a touch then ticks the card instead of opening it.
@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier, opens: Boolean = true) {
    Row(modifier) {
        Text(
            text = text,
            style = NotitiaTheme.typography.title3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = NotitiaTheme.spacing.s),
        )
        if (opens) CardChevron()
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
        sampleItems(Clock.System.now()).take(2).forEach { meeting ->
            NotitiaCard(onClick = {}) { CardTitle(meeting.title) }
        }
    }
}
