package com.example.notitia.ui.meetings

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.example.notitia.ui.components.HeaderTextAction
import com.example.notitia.ui.components.SelectionCheck
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.SelectionStyle
import com.example.notitia.ui.theme.selectionStyle
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.close
import notitia.shared.generated.resources.delete
import notitia.shared.generated.resources.meeting_delete
import notitia.shared.generated.resources.meetings_all
import notitia.shared.generated.resources.meetings_chosen
import notitia.shared.generated.resources.meetings_select_all
import notitia.shared.generated.resources.meetings_select_none
import notitia.shared.generated.resources.meetings_selection_close
import notitia.shared.generated.resources.meetings_selection_done
import notitia.shared.generated.resources.meetings_unselect_all
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// Over the header while choosing, the way each system does it. Android: Material's contextual bar, ✕ and the count,
// then « Tout » and the bin. iPhone: a navigation bar, « Tout sélectionner » on the left, the
// count in the middle, « OK » on the right; the bin waits in the toolbar that takes the tab bar's place.
// A Surface, which swallows touches: nothing under the bar opens through it.
@Composable
fun SelectionBar(
    count: Int,
    allChosen: Boolean,
    onToggleAll: () -> Unit,
    onDone: () -> Unit,
    onDelete: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = NotitiaTheme.colors.background, modifier = modifier.fillMaxWidth()) {
        when (selectionStyle) {
            SelectionStyle.ContextualBar -> ContextualBar(count, allChosen, onToggleAll, onDone, onDelete)
            SelectionStyle.Toolbar -> NavigationBar(count, allChosen, onToggleAll, onDone)
        }
    }
}

// The iPhone's toolbar in the tab bar's place, as in Photos: the bin on the right, in red, greyed until a meeting
// is chosen.
@Composable
fun SelectionToolbar(count: Int, onDelete: (Rect?) -> Unit, modifier: Modifier = Modifier) {
    val colors = NotitiaTheme.colors
    Column(modifier.background(colors.bar)) {
        HorizontalDivider(color = colors.divider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .height(NotitiaTheme.sizes.tabBar)
                .padding(horizontal = NotitiaTheme.spacing.s),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeleteButton(tint = colors.red, enabled = count > 0, onDelete = onDelete)
        }
    }
}

@Composable
private fun ContextualBar(
    count: Int,
    allChosen: Boolean,
    onToggleAll: () -> Unit,
    onDone: () -> Unit,
    onDelete: (Rect?) -> Unit,
) {
    val colors = NotitiaTheme.colors
    Row(
        modifier = Modifier
            .height(NotitiaTheme.sizes.selectionBar)
            .padding(horizontal = NotitiaTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIcon(Res.drawable.close, stringResource(Res.string.meetings_selection_close), onDone)
        Count(
            text = pluralStringResource(Res.plurals.meetings_chosen, count, count),
            modifier = Modifier.weight(1f).padding(start = NotitiaTheme.spacing.s),
        )
        ChooseAll(allChosen, onToggleAll)
        DeleteButton(tint = colors.text, enabled = true, onDelete = onDelete)
    }
}

@Composable
private fun NavigationBar(count: Int, allChosen: Boolean, onToggleAll: () -> Unit, onDone: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(NotitiaTheme.sizes.selectionBar)
            .padding(horizontal = NotitiaTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        HeaderTextAction(toggleAllLabel(allChosen), onToggleAll, Modifier.align(Alignment.CenterStart))
        Count(
            text = if (count == 0) {
                stringResource(Res.string.meetings_select_none)
            } else {
                pluralStringResource(Res.plurals.meetings_chosen, count, count)
            },
        )
        HeaderTextAction(stringResource(Res.string.meetings_selection_done), onDone, Modifier.align(Alignment.CenterEnd))
    }
}

// The cards' own circle, filled once all are chosen, as One UI's « Tout »: it shows the state, which an icon
// alone cannot. Read as a checkbox, « Tout sélectionner ».
@Composable
private fun ChooseAll(allChosen: Boolean, onToggleAll: () -> Unit) {
    val label = stringResource(Res.string.meetings_select_all)
    Row(
        modifier = Modifier
            .height(NotitiaTheme.sizes.touchTarget)
            .clip(NotitiaTheme.shapes.full)
            .toggleable(value = allChosen, role = Role.Checkbox, onValueChange = { onToggleAll() })
            .semantics { contentDescription = label }
            .padding(horizontal = NotitiaTheme.spacing.s),
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionCheck(allChosen)
        Text(
            text = stringResource(Res.string.meetings_all),
            style = NotitiaTheme.typography.label,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun toggleAllLabel(allChosen: Boolean): String =
    stringResource(if (allChosen) Res.string.meetings_unselect_all else Res.string.meetings_select_all)

// Read out when it changes, so a screen reader hears each meeting ticked.
@Composable
private fun Count(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = NotitiaTheme.typography.title3,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun BarIcon(
    icon: DrawableResource,
    description: String,
    onClick: () -> Unit,
    tint: Color = NotitiaTheme.colors.text,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(NotitiaTheme.sizes.touchTarget)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = if (enabled) tint else NotitiaTheme.colors.textTertiary,
            modifier = Modifier.size(NotitiaTheme.sizes.icon),
        )
    }
}

// Tells where it sits, so the iPhone's confirmation grows out of it.
@Composable
private fun DeleteButton(tint: Color, enabled: Boolean, onDelete: (Rect?) -> Unit) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    BarIcon(
        icon = Res.drawable.delete,
        description = stringResource(Res.string.meeting_delete),
        onClick = { onDelete(bounds) },
        tint = tint,
        enabled = enabled,
        modifier = Modifier.onGloballyPositioned { bounds = it.boundsInWindow() },
    )
}

@NotitiaPreviews
@Composable
private fun SelectionBarPreview() = NotitiaTheme {
    Column {
        SelectionBar(count = 2, allChosen = false, onToggleAll = {}, onDone = {}, onDelete = {})
        SelectionToolbar(count = 2, onDelete = {})
    }
}
