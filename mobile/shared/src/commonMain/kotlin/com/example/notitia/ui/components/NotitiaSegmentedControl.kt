package com.example.notitia.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.minutes_tab_minutes
import notitia.shared.generated.resources.minutes_tab_transcript
import org.jetbrains.compose.resources.stringResource

// Material's segmented buttons draw outlines and check marks: Notitia's control is a quieter pill.
@Composable
fun NotitiaSegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NotitiaTheme.colors
    val shape = NotitiaTheme.shapes.full
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .padding(NotitiaTheme.spacing.xs)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.xs),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            val background by animateColorAsState(
                targetValue = if (isSelected) colors.surfaceRaised else Color.Transparent,
                animationSpec = NotitiaTheme.motion.quick(),
                label = "segment",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = NotitiaTheme.sizes.buttonCompact)
                    .clip(shape)
                    .background(background)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = NotitiaTheme.spacing.m, vertical = NotitiaTheme.spacing.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = NotitiaTheme.typography.labelSmall,
                    color = if (isSelected) colors.text else colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@NotitiaPreviews
@Composable
private fun NotitiaSegmentedControlPreview() = NotitiaTheme {
    Box(Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l)) {
        NotitiaSegmentedControl(
            options = listOf(stringResource(Res.string.minutes_tab_minutes), stringResource(Res.string.minutes_tab_transcript)),
            selected = 0,
            onSelect = {},
        )
    }
}
