package com.example.notitia.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import com.example.notitia.ui.navigation.Tab
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun NotitiaTabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(NotitiaTheme.colors.background)) {
        HorizontalDivider(color = NotitiaTheme.colors.divider)
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .height(IntrinsicSize.Min)
                .selectableGroup(),
        ) {
            Tab.entries.forEach { tab ->
                TabItem(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = NotitiaTheme.colors
    val motion = NotitiaTheme.motion
    val transition = updateTransition(selected, label = "tab")
    val pill by transition.animateFloat(
        transitionSpec = { if (targetState) motion.standard() else motion.instant() },
        label = "pill",
    ) { if (it) 1f else 0f }
    val bold by transition.animateFloat(
        transitionSpec = { if (targetState) motion.quick() else motion.instant() },
        label = "bold",
    ) { if (it) 1f else 0f }
    val contentColor by transition.animateColor(
        transitionSpec = { if (targetState) motion.quick() else motion.instant() },
        label = "color",
    ) { if (it) colors.accentContent else colors.textSecondary }
    val gesture = remember { Animatable(1f) }
    LaunchedEffect(selected) { if (!selected) gesture.snapTo(1f) }
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                // In onClick rather than on selection, so touching the selected tab replays the gesture.
                onClick = {
                    scope.launch {
                        if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return@launch
                        gesture.snapTo(0f)
                        // Linear, since each icon shapes its own curves.
                        gesture.animateTo(1f, tween(motion.TAB_ICON_MS, easing = LinearEasing))
                    }
                    onClick()
                },
            )
            .heightIn(min = NotitiaTheme.sizes.tabBar)
            .padding(
                start = NotitiaTheme.spacing.xs,
                top = NotitiaTheme.spacing.s,
                end = NotitiaTheme.spacing.xs,
                bottom = NotitiaTheme.spacing.xs,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Top-aligned, so the icons stay in line when one label wraps.
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(NotitiaTheme.sizes.tabPillWidth, NotitiaTheme.sizes.tabPillHeight)
                .clip(NotitiaTheme.shapes.full)
                .indication(interactionSource, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = pill
                        alpha = pill
                    }
                    .background(colors.accentSoft),
            )
            TabIcon(tab, weight = { bold }, gesture = { gesture.value }, color = contentColor)
        }
        // At 200 % text a label can wrap: it breaks at a hyphen rather than mid-word.
        Text(
            text = stringResource(tab.title),
            style = NotitiaTheme.typography.tab.copy(hyphens = Hyphens.Auto),
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}

@NotitiaPreviews
@Composable
private fun NotitiaTabBarPreview() = NotitiaTheme {
    var selected by remember { mutableStateOf(Tab.Meetings) }
    NotitiaTabBar(selected = selected, onSelect = { selected = it })
}
