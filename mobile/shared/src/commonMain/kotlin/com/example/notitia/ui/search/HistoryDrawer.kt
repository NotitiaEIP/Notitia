package com.example.notitia.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min
import com.example.notitia.ui.components.GroupTitle
import com.example.notitia.ui.meetings.label
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import kotlin.time.Clock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.chat_bubble
import notitia.shared.generated.resources.edit_square
import notitia.shared.generated.resources.search
import notitia.shared.generated.resources.search_history_clear
import notitia.shared.generated.resources.search_history_cleared
import notitia.shared.generated.resources.search_history_empty
import notitia.shared.generated.resources.search_history_title
import notitia.shared.generated.resources.search_new
import notitia.shared.generated.resources.undo
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

// On a small phone, the screen behind keeps a fifth of the width: enough to see it and touch it to close.
private const val DRAWER_SHARE = 0.8f

// Buttons and callbacks move the drawer through here. Only the release of a drag keeps Material's motion:
// it follows the finger.
suspend fun DrawerState.move(open: Boolean, reduced: Boolean) {
    when {
        reduced -> snapTo(if (open) DrawerValue.Open else DrawerValue.Closed)
        open -> open()
        else -> close()
    }
}

// Slides over the screen from the left, as in Claude. Material's sheet handles back, with the predictive back
// animation on Android 14+.
@Composable
fun SearchHistoryDrawer(drawerState: DrawerState, state: SearchState) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val reduced = reducedMotion()
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    val cleared = stringResource(Res.string.search_history_cleared)
    val undo = stringResource(Res.string.undo)
    val window = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val width = min(window * DRAWER_SHARE, NotitiaTheme.sizes.sidePanel)
    val closedOffset = with(density) { -width.toPx() }

    // The keyboard leaves as soon as the drawer starts to come in, not once it is open.
    LaunchedEffect(drawerState, closedOffset) {
        snapshotFlow { drawerState.currentOffset > closedOffset }
            .distinctUntilChanged()
            .collect { coming -> if (coming) focusManager.clearFocus() }
    }

    fun close() {
        scope.launch { drawerState.move(open = false, reduced) }
    }

    fun offerUndo(message: String, onUndo: () -> Unit) {
        scope.launch {
            snackbars.currentSnackbarData?.dismiss()
            val result = snackbars.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    ModalDrawerSheet(
        drawerState = drawerState,
        modifier = Modifier.width(width),
        drawerContainerColor = NotitiaTheme.colors.surface,
        // Material would derive a grey from our surface colour: every text in the drawer gets its own colour.
        drawerContentColor = NotitiaTheme.colors.text,
    ) {
        Box(Modifier.fillMaxSize()) {
            HistoryList(
                history = state.history,
                searched = state.step.searched,
                onNewSearch = {
                    state.startNewSearch()
                    close()
                },
                onOpen = { entry ->
                    state.open(entry)
                    close()
                },
                onClear = {
                    state.clearHistory()
                    offerUndo(cleared, state::undoClear)
                },
            )
            // Where the history was just cleared.
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
        }
    }
}

// One grid for the drawer: highlights reach the margin; the day titles sit closer to the edge than the searches,
// so they read as headings; everything else starts on the line of the icons.
private class DrawerGrid(val margin: Dp, val groupStart: Dp, val inset: Dp, val iconGap: Dp)

@Composable
private fun drawerGrid(): DrawerGrid {
    val spacing = NotitiaTheme.spacing
    return DrawerGrid(margin = spacing.m, groupStart = spacing.s, inset = spacing.l + spacing.xs, iconGap = spacing.m)
}

@Composable
internal fun HistoryList(
    history: List<HistoryEntry>,
    searched: String?,
    onNewSearch: () -> Unit,
    onOpen: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NotitiaTheme.colors
    val spacing = NotitiaTheme.spacing
    val typography = NotitiaTheme.typography
    val grid = drawerGrid()
    val groups = remember(history) { historyByDay(history, Clock.System.now(), TimeZone.currentSystemDefault()) }
    val titleLine = with(LocalDensity.current) { typography.display.lineHeight.toDp() }

    Column(modifier.fillMaxSize().padding(horizontal = grid.margin)) {
        Text(
            text = stringResource(Res.string.search_history_title),
            style = typography.title1,
            color = colors.text,
            modifier = Modifier
                .padding(top = spacing.s, start = grid.inset, end = grid.inset)
                // On the line of the large title of the screen behind.
                .height(titleLine)
                .wrapContentHeight(Alignment.CenterVertically)
                .semantics { heading() },
        )
        PanelRow(
            icon = Res.drawable.edit_square,
            text = stringResource(Res.string.search_new),
            style = typography.label,
            tint = colors.accentContent,
            textColor = colors.accentContent,
            onClick = onNewSearch,
            modifier = Modifier.padding(top = spacing.s),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = spacing.s),
        ) {
            if (history.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(Res.string.search_history_empty),
                        style = typography.callout,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = grid.inset, vertical = spacing.m),
                    )
                }
            }
            groups.forEach { (group, entries) ->
                item(key = "group-$group") {
                    GroupTitle(group.label(), Modifier.padding(start = grid.groupStart, top = spacing.m, bottom = spacing.xs))
                }
                items(entries, key = { "entry-${questionKey(it.text)}" }) { entry ->
                    val shown = searched != null && sameQuestion(entry.text, searched)
                    PanelRow(
                        icon = if (entry.kind == QueryKind.Question) Res.drawable.chat_bubble else Res.drawable.search,
                        text = entry.text,
                        style = typography.body,
                        tint = if (shown) colors.accentContent else colors.textSecondary,
                        textColor = if (shown) colors.accentContent else colors.text,
                        onClick = { onOpen(entry) },
                        highlighted = shown,
                    )
                }
            }
        }
        if (history.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .padding(bottom = spacing.s)
                    .heightIn(min = NotitiaTheme.sizes.touchTarget)
                    .clip(NotitiaTheme.shapes.full)
                    .clickable(role = Role.Button, onClick = onClear)
                    .padding(horizontal = grid.inset),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(Res.string.search_history_clear),
                    style = typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun PanelRow(
    icon: DrawableResource,
    text: String,
    style: TextStyle,
    tint: Color,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val grid = drawerGrid()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NotitiaTheme.sizes.touchTarget)
            .clip(NotitiaTheme.shapes.full)
            .then(if (highlighted) Modifier.background(NotitiaTheme.colors.accentSoft) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (highlighted) selected = true }
            .padding(horizontal = grid.inset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(grid.iconGap),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(NotitiaTheme.sizes.iconInButton),
        )
        Text(text = text, style = style, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HistoryListPreview(history: List<HistoryEntry>, searched: String?) = NotitiaTheme {
    HistoryList(
        history = history,
        searched = searched,
        onNewSearch = {},
        onOpen = {},
        onClear = {},
        modifier = Modifier.background(NotitiaTheme.colors.surface),
    )
}

@NotitiaPreviews
@Composable
private fun HistoryListSamplesPreview() {
    val history = remember { sampleHistory(Clock.System.now()) }
    HistoryListPreview(history, searched = history[1].text)
}

@NotitiaPreviews
@Composable
private fun HistoryListEmptyPreview() = HistoryListPreview(emptyList(), searched = null)
