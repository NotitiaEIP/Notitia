package com.example.notitia.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min
import com.example.notitia.ui.components.ContextMenu
import com.example.notitia.ui.components.ContextMenuItem
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
import notitia.shared.generated.resources.cancel
import notitia.shared.generated.resources.chat_bubble
import notitia.shared.generated.resources.delete
import notitia.shared.generated.resources.edit
import notitia.shared.generated.resources.edit_square
import notitia.shared.generated.resources.keep
import notitia.shared.generated.resources.keep_off
import notitia.shared.generated.resources.search
import notitia.shared.generated.resources.search_delete
import notitia.shared.generated.resources.search_deleted
import notitia.shared.generated.resources.search_history_clear
import notitia.shared.generated.resources.search_history_cleared
import notitia.shared.generated.resources.search_history_empty
import notitia.shared.generated.resources.search_history_title
import notitia.shared.generated.resources.search_new
import notitia.shared.generated.resources.search_options
import notitia.shared.generated.resources.search_pin
import notitia.shared.generated.resources.search_pinned
import notitia.shared.generated.resources.search_rename
import notitia.shared.generated.resources.search_rename_title
import notitia.shared.generated.resources.search_unpin
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
    val deleted = stringResource(Res.string.search_deleted)
    val undo = stringResource(Res.string.undo)
    val window = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val width = min(window * DRAWER_SHARE, NotitiaTheme.sizes.sidePanel)
    val closedOffset = with(density) { -width.toPx() }
    var renaming by remember { mutableStateOf<HistoryEntry?>(null) }

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
                onPin = state::pin,
                onRename = { renaming = it },
                onDelete = { entry ->
                    state.delete(entry)
                    offerUndo(deleted, state::undoDelete)
                },
                onClear = {
                    state.clearHistory()
                    offerUndo(cleared, state::undoClear)
                },
            )
            // Where the history was just changed.
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
        }
    }

    renaming?.let { entry ->
        RenameDialog(
            entry = entry,
            onRename = { title ->
                state.rename(entry, title)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
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
    onPin: (HistoryEntry, Boolean) -> Unit,
    onRename: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NotitiaTheme.colors
    val spacing = NotitiaTheme.spacing
    val typography = NotitiaTheme.typography
    val grid = drawerGrid()
    val groups = remember(history) { historyGroups(history, Clock.System.now(), TimeZone.currentSystemDefault()) }
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
                    HistoryRow(
                        entry = entry,
                        shown = searched != null && sameQuestion(entry.text, searched),
                        onOpen = { onOpen(entry) },
                        onPin = { onPin(entry, !entry.pinned) },
                        onRename = { onRename(entry) },
                        onDelete = { onDelete(entry) },
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
private fun HistoryGroup.label(): String = when (this) {
    HistoryGroup.Pinned -> stringResource(Res.string.search_pinned)
    is HistoryGroup.Day -> group.label()
}

// A long press opens the actions next to the search, as a context menu; screen readers get them as actions.
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    shown: Boolean,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = NotitiaTheme.colors
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    // Run once the menu has faded out.
    var afterMenu by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun choose(action: () -> Unit) {
        afterMenu = action
        menu = false
    }
    val pinLabel = stringResource(if (entry.pinned) Res.string.search_unpin else Res.string.search_pin)
    val renameLabel = stringResource(Res.string.search_rename)
    val deleteLabel = stringResource(Res.string.search_delete)

    Box {
        PanelRow(
            icon = if (entry.kind == QueryKind.Question) Res.drawable.chat_bubble else Res.drawable.search,
            text = entry.label,
            style = NotitiaTheme.typography.body,
            tint = if (shown) colors.accentContent else colors.textSecondary,
            textColor = if (shown) colors.accentContent else colors.text,
            onClick = onOpen,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menu = true
            },
            longClickLabel = stringResource(Res.string.search_options),
            actions = listOf(
                CustomAccessibilityAction(pinLabel) { onPin(); true },
                CustomAccessibilityAction(renameLabel) { onRename(); true },
                CustomAccessibilityAction(deleteLabel) { onDelete(); true },
            ),
            highlighted = shown,
        )
        ContextMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            onClosed = {
                afterMenu?.invoke()
                afterMenu = null
            },
        ) {
            val pinIcon = if (entry.pinned) Res.drawable.keep_off else Res.drawable.keep
            ContextMenuItem(pinIcon, pinLabel, colors.text) { choose(onPin) }
            ContextMenuItem(Res.drawable.edit, renameLabel, colors.text) { choose(onRename) }
            ContextMenuItem(Res.drawable.delete, deleteLabel, colors.red) { choose(onDelete) }
        }
    }
}

// Renames what the list shows; opening the search still launches its text.
@Composable
private fun RenameDialog(entry: HistoryEntry, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = NotitiaTheme.colors
    val field = rememberTextFieldState(entry.label, initialSelection = TextRange(0, entry.label.length))
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val buttons = ButtonDefaults.textButtonColors(contentColor = colors.accentContent)
    val canRename = field.text.isNotBlank()
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.search_rename_title), style = NotitiaTheme.typography.title3) },
        text = {
            OutlinedTextField(
                state = field,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = NotitiaTheme.typography.body,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = { if (canRename) onRename(field.text.toString()) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(field.text.toString()) }, enabled = canRename, colors = buttons) {
                Text(stringResource(Res.string.search_rename), style = NotitiaTheme.typography.label)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = buttons) {
                Text(stringResource(Res.string.cancel), style = NotitiaTheme.typography.label)
            }
        },
    )
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
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    actions: List<CustomAccessibilityAction> = emptyList(),
    highlighted: Boolean = false,
) {
    val grid = drawerGrid()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NotitiaTheme.sizes.touchTarget)
            .clip(NotitiaTheme.shapes.full)
            .then(if (highlighted) Modifier.background(NotitiaTheme.colors.accentSoft) else Modifier)
            .combinedClickable(
                role = Role.Button,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics {
                if (highlighted) selected = true
                if (actions.isNotEmpty()) customActions = actions
            }
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
        onPin = { _, _ -> },
        onRename = {},
        onDelete = {},
        onClear = {},
        modifier = Modifier.background(NotitiaTheme.colors.surface),
    )
}

@NotitiaPreviews
@Composable
private fun HistoryListSamplesPreview() {
    val history = remember { sampleHistory(Clock.System.now()).let { it.pin(it[2], pinned = true) } }
    HistoryListPreview(history, searched = history[1].text)
}

@NotitiaPreviews
@Composable
private fun HistoryListEmptyPreview() = HistoryListPreview(emptyList(), searched = null)
