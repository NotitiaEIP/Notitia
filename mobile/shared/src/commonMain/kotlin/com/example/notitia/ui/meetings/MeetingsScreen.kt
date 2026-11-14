package com.example.notitia.ui.meetings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.notitia.ui.components.ContextMenu
import com.example.notitia.ui.components.ContextMenuItem
import com.example.notitia.ui.components.DestructiveConfirmation
import com.example.notitia.ui.components.EmptyState
import com.example.notitia.ui.components.GroupTitle
import com.example.notitia.ui.components.HeaderAction
import com.example.notitia.ui.components.HeaderTextAction
import com.example.notitia.ui.components.MeetingCard
import com.example.notitia.ui.components.RecordButton
import com.example.notitia.ui.components.ScreenHeader
import com.example.notitia.ui.components.listMotion
import com.example.notitia.ui.components.rememberContextMenuState
import com.example.notitia.ui.components.rememberToday
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.SelectionStyle
import com.example.notitia.ui.theme.selectionStyle
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.check_circle
import notitia.shared.generated.resources.delete
import notitia.shared.generated.resources.meeting_delete
import notitia.shared.generated.resources.meetings_empty
import notitia.shared.generated.resources.meetings_select
import notitia.shared.generated.resources.meetings_title
import notitia.shared.generated.resources.search_options
import notitia.shared.generated.resources.settings
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun MeetingsScreen(
    meetings: List<MeetingListItem>,
    recording: Boolean,
    selection: MeetingSelection,
    onOpen: (MeetingListItem) -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
    onDelete: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ids = remember(meetings) { meetings.map { it.id } }
    val chosen = selection.chosen
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = chosen != null,
        onBackCompleted = selection::end,
    )
    Box(modifier.fillMaxSize()) {
        if (meetings.isEmpty()) {
            EmptyMeetings(onSettings)
        } else {
            MeetingList(meetings, selection, onOpen, onSettings)
        }
        AnimatedVisibility(
            visible = chosen != null,
            enter = fadeIn(NotitiaTheme.motion.quick()),
            exit = fadeOut(NotitiaTheme.motion.quick()),
        ) {
            SelectionBar(
                count = chosen.orEmpty().size,
                allChosen = selection.allChosen(ids),
                onToggleAll = { selection.toggleAll(ids) },
                onDone = selection::end,
                onDelete = selection::askToDelete,
            )
        }
        // While a recording runs, the mini bar stands in for the button: a second one could not start anything.
        // While choosing, it would start a meeting in the middle of a tidy-up.
        if (!recording && chosen == null) {
            RecordButton(
                onClick = onRecord,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = NotitiaTheme.spacing.xl),
            )
        }
    }
    DeleteConfirmation(meetings, selection, onDelete)
}

@Composable
private fun DeleteConfirmation(
    meetings: List<MeetingListItem>,
    selection: MeetingSelection,
    onDelete: (Set<String>) -> Unit,
) {
    val request = selection.deleteRequest
    val alone = request?.ids?.singleOrNull()?.let { id -> meetings.firstOrNull { it.id == id } }
    DestructiveConfirmation(
        destruction = request?.let { if (alone != null) meetingDeletion(alone.title) else meetingsDeletion(it.ids.size) },
        anchor = request?.anchor,
        onConfirm = {
            request?.let { onDelete(it.ids) }
            selection.end()
        },
        onDismiss = selection::cancelDelete,
    )
}

// onSelect: the iPhone's « Sélectionner », shown only when there is something to choose.
@Composable
private fun Header(onSettings: () -> Unit, onSelect: (() -> Unit)? = null) {
    ScreenHeader(title = stringResource(Res.string.meetings_title)) {
        onSelect?.let { HeaderTextAction(stringResource(Res.string.meetings_select), it) }
        HeaderAction(
            icon = painterResource(Res.drawable.settings),
            description = stringResource(Res.string.settings),
            onClick = onSettings,
        )
    }
}

@Composable
private fun EmptyMeetings(onSettings: () -> Unit) {
    Column(Modifier.padding(horizontal = NotitiaTheme.spacing.l)) {
        Header(onSettings)
        EmptyState(
            message = stringResource(Res.string.meetings_empty),
            modifier = Modifier.padding(top = NotitiaTheme.spacing.xxxl),
        )
    }
}

@Composable
private fun MeetingList(
    meetings: List<MeetingListItem>,
    selection: MeetingSelection,
    onOpen: (MeetingListItem) -> Unit,
    onSettings: () -> Unit,
) {
    val chosen = selection.chosen
    val today = rememberToday()
    val now = remember(meetings, today) { Clock.System.now() }
    val groups = remember(meetings, now) { groupByDay(meetings, now, TimeZone.currentSystemDefault()) }
    val spacing = NotitiaTheme.spacing
    LazyColumn(
        // Room under the last card, so the record button never hides it.
        contentPadding = PaddingValues(
            start = spacing.l,
            end = spacing.l,
            bottom = NotitiaTheme.sizes.recordButton + spacing.xl * 2,
        ),
    ) {
        item(key = "header") {
            Header(
                onSettings = onSettings,
                onSelect = if (selectionStyle == SelectionStyle.Toolbar && chosen == null) ({ selection.start() }) else null,
            )
        }
        groups.forEach { (group, inGroup) ->
            item(key = group.toString()) {
                GroupTitle(group.label(), listMotion().padding(top = spacing.xl, bottom = spacing.s))
            }
            items(inGroup, key = { it.id }) { meeting ->
                MeetingRow(meeting, now, selection, onOpen, listMotion().padding(bottom = spacing.s))
            }
        }
    }
}

// A long press, the way each system does it. Android: it starts choosing with its card, then ticks like a touch, as
// in WhatsApp and Gmail. iPhone: it opens the card's menu, as in Mail and WhatsApp: « Sélectionner » starts choosing
// with it, « Supprimer » asks first, in red and last; while choosing, it does nothing.
@Composable
private fun MeetingRow(
    meeting: MeetingListItem,
    now: Instant,
    selection: MeetingSelection,
    onOpen: (MeetingListItem) -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val menu = rememberContextMenuState()
    val chosen = selection.chosen
    val selects = selectionStyle == SelectionStyle.ContextualBar
    // The iPhone's confirmation grows out of the card.
    var bounds by remember { mutableStateOf<Rect?>(null) }
    Box(modifier.onGloballyPositioned { bounds = it.boundsInWindow() }) {
        MeetingCard(
            meeting = meeting,
            now = now,
            onClick = { if (chosen != null) selection.toggle(meeting.id) else onOpen(meeting) },
            selected = chosen?.let { meeting.id in it },
            onLongClick = when {
                chosen != null -> if (selects) ({ selection.toggle(meeting.id) }) else null
                else -> {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (selects) selection.start(meeting.id) else menu.open()
                    }
                }
            },
            longClickLabel = stringResource(if (selects) Res.string.meetings_select else Res.string.search_options),
        )
        if (!selects) {
            ContextMenu(menu) {
                ContextMenuItem(
                    Res.drawable.check_circle,
                    stringResource(Res.string.meetings_select),
                    NotitiaTheme.colors.text,
                ) { menu.choose { selection.start(meeting.id) } }
                ContextMenuItem(Res.drawable.delete, stringResource(Res.string.meeting_delete), NotitiaTheme.colors.red) {
                    menu.choose { selection.askToDelete(meeting.id, bounds) }
                }
            }
        }
    }
}

@NotitiaPreviews
@Composable
private fun MeetingsScreenPreview() = NotitiaTheme {
    MeetingsScreen(
        meetings = sampleItems(Clock.System.now()),
        recording = false,
        selection = remember { MeetingSelection() },
        onOpen = {},
        onRecord = {},
        onSettings = {},
        onDelete = {},
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}

@NotitiaPreviews
@Composable
private fun EmptyMeetingsScreenPreview() = NotitiaTheme {
    MeetingsScreen(
        meetings = emptyList(),
        recording = false,
        selection = remember { MeetingSelection() },
        onOpen = {},
        onRecord = {},
        onSettings = {},
        onDelete = {},
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}

@NotitiaPreviews
@Composable
private fun ChoosingMeetingsScreenPreview() = NotitiaTheme {
    val meetings = sampleItems(Clock.System.now())
    MeetingsScreen(
        meetings = meetings,
        recording = false,
        selection = remember { MeetingSelection(chosen = setOf(meetings.first().id)) },
        onOpen = {},
        onRecord = {},
        onSettings = {},
        onDelete = {},
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}
