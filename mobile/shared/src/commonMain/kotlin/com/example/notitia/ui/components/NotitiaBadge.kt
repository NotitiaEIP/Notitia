package com.example.notitia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import com.example.notitia.ui.theme.NotitiaColors
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.actions
import notitia.shared.generated.resources.check
import notitia.shared.generated.resources.minutes_ready
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

enum class BadgeTone {
    Accent,
    Success;

    fun container(colors: NotitiaColors): Color = when (this) {
        Accent -> colors.accentSoft
        Success -> colors.greenSoft
    }

    fun content(colors: NotitiaColors): Color = when (this) {
        Accent -> colors.accentContent
        Success -> colors.green
    }
}

// The badge says the state in words: its colour alone never carries the meaning.
@Composable
fun NotitiaBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: BadgeTone = BadgeTone.Accent,
    icon: Painter? = null,
) {
    val colors = NotitiaTheme.colors
    val content = tone.content(colors)
    Row(
        modifier = modifier
            .heightIn(min = NotitiaTheme.sizes.badge)
            .background(tone.container(colors), NotitiaTheme.shapes.badge)
            .padding(horizontal = NotitiaTheme.spacing.s, vertical = NotitiaTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(NotitiaTheme.sizes.iconSmall),
            )
        }
        Text(text = text, style = NotitiaTheme.typography.badge, color = content)
    }
}

@NotitiaPreviews
@Composable
private fun NotitiaBadgePreview() = NotitiaTheme {
    Box(Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l)) {
        Row(horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s)) {
            NotitiaBadge(text = pluralStringResource(Res.plurals.actions, 3, 3))
            NotitiaBadge(
                text = stringResource(Res.string.minutes_ready),
                tone = BadgeTone.Success,
                icon = painterResource(Res.drawable.check),
            )
        }
    }
}
