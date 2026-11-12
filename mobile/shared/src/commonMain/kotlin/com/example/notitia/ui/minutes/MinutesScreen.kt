package com.example.notitia.ui.minutes

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.TranscriptLine
import com.example.notitia.ui.components.ActionRow
import com.example.notitia.ui.components.BackButton
import com.example.notitia.ui.components.ContextMenu
import com.example.notitia.ui.components.ContextMenuItem
import com.example.notitia.ui.components.Destruction
import com.example.notitia.ui.components.DestructiveConfirmation
import com.example.notitia.ui.components.DetailsLine
import com.example.notitia.ui.components.NotitiaButton
import com.example.notitia.ui.components.NotitiaSegmentedControl
import com.example.notitia.ui.components.SectionHeader
import com.example.notitia.ui.components.SpeakerChip
import com.example.notitia.ui.components.rememberContextMenuState
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.sampleMinutes
import com.example.notitia.ui.meetings.lengthLabel
import com.example.notitia.ui.meetings.meetingWhenLabel
import com.example.notitia.ui.recording.timerText
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.shareIcon
import kotlin.time.Clock
import kotlin.time.Instant
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.delete
import notitia.shared.generated.resources.edit
import notitia.shared.generated.resources.meeting_delete
import notitia.shared.generated.resources.meeting_delete_confirm
import notitia.shared.generated.resources.meeting_delete_message
import notitia.shared.generated.resources.meeting_delete_title
import notitia.shared.generated.resources.meeting_rename
import notitia.shared.generated.resources.meetings_title
import notitia.shared.generated.resources.minutes_actions
import notitia.shared.generated.resources.minutes_back_description
import notitia.shared.generated.resources.minutes_decisions
import notitia.shared.generated.resources.minutes_failed
import notitia.shared.generated.resources.minutes_more
import notitia.shared.generated.resources.minutes_no_actions
import notitia.shared.generated.resources.minutes_no_decisions
import notitia.shared.generated.resources.minutes_no_summary
import notitia.shared.generated.resources.minutes_share
import notitia.shared.generated.resources.minutes_summary
import notitia.shared.generated.resources.minutes_tab_minutes
import notitia.shared.generated.resources.minutes_tab_transcript
import notitia.shared.generated.resources.minutes_unavailable
import notitia.shared.generated.resources.minutes_writing
import notitia.shared.generated.resources.more_horiz
import notitia.shared.generated.resources.participants
import notitia.shared.generated.resources.transcript_empty
import notitia.shared.generated.resources.transcript_failed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun MinutesRoute(
    meeting: Meeting,
    item: MeetingListItem,
    onSetDone: (ActionId, Boolean) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    backLabel: String,
    backDescription: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = remember { Clock.System.now() }
    val minutes = meeting.toMinutes(item)
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        onBackCompleted = onBack,
    )
    MinutesScreen(
        minutes = minutes,
        notice = when (meeting.minutes) {
            MinutesState.Writing -> Res.string.minutes_writing
            MinutesState.Failed -> Res.string.minutes_failed
            MinutesState.Unavailable -> Res.string.minutes_unavailable
            is MinutesState.Written -> null
        },
        now = now,
        backLabel = backLabel,
        backDescription = backDescription,
        onBack = onBack,
        onToggleAction = { index -> onSetDone(ActionId(meeting.id, index), !minutes.actions[index].done) },
        onRename = onRename,
        onDelete = onDelete,
        transcript = meeting.transcript,
        modifier = modifier,
    )
}

// notice replaces the minutes when they are not written.
@Composable
fun MinutesScreen(
    minutes: Minutes,
    now: Instant,
    backLabel: String,
    backDescription: String,
    onBack: () -> Unit,
    onToggleAction: (Int) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    notice: StringResource? = null,
    transcript: List<TranscriptLine> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val spacing = NotitiaTheme.spacing
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val meeting = minutes.meeting
    val focusManager = LocalFocusManager.current
    var renameRequests by remember { mutableIntStateOf(0) }
    var confirmingDelete by remember { mutableStateOf(false) }
    // Where « … » sits: the iPhone's confirmation grows out of it.
    var moreButton by remember { mutableStateOf<Rect?>(null) }

    // A touch beside the title leaves it, and keeps what was typed.
    Column(modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }) {
        TopBar(
            backLabel = backLabel,
            backDescription = backDescription,
            onBack = onBack,
            onRename = { renameRequests++ },
            onDelete = { confirmingDelete = true },
            onMorePlaced = { moreButton = it },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.l)
                .padding(bottom = spacing.xl),
        ) {
            MeetingTitle(title = meeting.title, onRename = onRename, editRequests = renameRequests)
            DetailsLine(
                parts = listOf(
                    meetingWhenLabel(meeting.startedAt, now),
                    lengthLabel(meeting.duration),
                    pluralStringResource(Res.plurals.participants, meeting.participants, meeting.participants),
                ),
                modifier = Modifier.padding(top = spacing.xs),
            )
            if (minutes.speakers.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = spacing.l),
                    horizontalArrangement = Arrangement.spacedBy(spacing.s),
                    verticalArrangement = Arrangement.spacedBy(spacing.s),
                ) {
                    minutes.speakers.forEachIndexed { index, name -> SpeakerChip(name, index) }
                }
            }
            NotitiaSegmentedControl(
                options = listOf(
                    stringResource(Res.string.minutes_tab_minutes),
                    stringResource(Res.string.minutes_tab_transcript),
                ),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(top = spacing.xl),
            )
            when {
                tab != 0 -> TranscriptContent(transcript)
                notice != null -> Notice(stringResource(notice), Modifier.padding(top = spacing.xxl))
                else -> MinutesContent(minutes, onToggleAction)
            }
        }
        HorizontalDivider(color = NotitiaTheme.colors.divider)
        NotitiaButton(
            text = stringResource(Res.string.minutes_share),
            onClick = {},
            icon = painterResource(shareIcon),
            modifier = Modifier.fillMaxWidth().padding(spacing.l),
        )
    }
    DestructiveConfirmation(
        destruction = if (confirmingDelete) {
            Destruction(
                title = stringResource(Res.string.meeting_delete_title, meeting.title),
                message = stringResource(Res.string.meeting_delete_message),
                confirm = stringResource(Res.string.meeting_delete_confirm),
                shortConfirm = stringResource(Res.string.meeting_delete),
            )
        } else {
            null
        },
        anchor = moreButton,
        onConfirm = {
            confirmingDelete = false
            onDelete()
        },
        onDismiss = { confirmingDelete = false },
    )
}

// The meeting's own actions, as in Notes: rename puts the cursor in the title, delete asks first, in red and last.
@Composable
private fun TopBar(
    backLabel: String,
    backDescription: String,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMorePlaced: (Rect) -> Unit,
) {
    val colors = NotitiaTheme.colors
    val menu = rememberContextMenuState()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NotitiaTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(backLabel, backDescription, onBack)
        Box(Modifier.weight(1f))
        Box {
            IconButton(
                onClick = menu::open,
                modifier = Modifier
                    .size(NotitiaTheme.sizes.touchTarget)
                    .onGloballyPositioned { onMorePlaced(it.boundsInWindow()) },
            ) {
                Icon(
                    painter = painterResource(Res.drawable.more_horiz),
                    contentDescription = stringResource(Res.string.minutes_more),
                    tint = colors.text,
                )
            }
            ContextMenu(menu) {
                ContextMenuItem(Res.drawable.edit, stringResource(Res.string.meeting_rename), colors.text) {
                    menu.choose(onRename)
                }
                ContextMenuItem(Res.drawable.delete, stringResource(Res.string.meeting_delete), colors.red) {
                    menu.choose(onDelete)
                }
            }
        }
    }
}

@Composable
private fun MinutesContent(minutes: Minutes, onToggleAction: (Int) -> Unit) {
    val spacing = NotitiaTheme.spacing
    SectionHeader(
        title = stringResource(Res.string.minutes_decisions),
        count = minutes.decisions.size,
        modifier = Modifier.padding(top = spacing.xxl, bottom = spacing.s),
    )
    if (minutes.decisions.isEmpty()) Notice(stringResource(Res.string.minutes_no_decisions))
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s)) {
        minutes.decisions.forEach { Bullet(it) }
    }

    SectionHeader(
        title = stringResource(Res.string.minutes_actions),
        count = minutes.actions.size,
        modifier = Modifier.padding(top = spacing.xxl, bottom = spacing.xs),
    )
    if (minutes.actions.isEmpty()) Notice(stringResource(Res.string.minutes_no_actions))
    minutes.actions.forEachIndexed { index, action ->
        ActionRow(action = action, onToggle = { onToggleAction(index) }, speaker = minutes.speakerOf(action))
    }

    SectionHeader(
        title = stringResource(Res.string.minutes_summary),
        modifier = Modifier.padding(top = spacing.xxl, bottom = spacing.s),
    )
    if (minutes.summary.isEmpty()) {
        Notice(stringResource(Res.string.minutes_no_summary))
    } else {
        Text(text = minutes.summary, style = NotitiaTheme.typography.body)
    }
}

@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = NotitiaTheme.typography.body,
        color = NotitiaTheme.colors.textSecondary,
        modifier = modifier,
    )
}

@Composable
private fun TranscriptContent(lines: List<TranscriptLine>) {
    val colors = NotitiaTheme.colors
    val spacing = NotitiaTheme.spacing
    val typography = NotitiaTheme.typography
    Column(
        modifier = Modifier.padding(top = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        if (lines.isEmpty()) Notice(stringResource(Res.string.transcript_empty))
        lines.forEach { line ->
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                Text(text = timerText(line.at), style = typography.caption, color = colors.textSecondary)
                Text(
                    text = line.text ?: stringResource(Res.string.transcript_failed),
                    style = typography.body,
                    color = if (line.text == null) colors.textSecondary else colors.text,
                )
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    val style = NotitiaTheme.typography.body
    // As tall as the first line, so the dot stays centred on it whatever the text size.
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Row(horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.m)) {
        Box(Modifier.height(lineHeight), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(NotitiaTheme.sizes.bullet)
                    .background(NotitiaTheme.colors.textSecondary, NotitiaTheme.shapes.full),
            )
        }
        Text(text = text, style = style)
    }
}

@NotitiaPreviews
@Composable
private fun MinutesScreenPreview() = NotitiaTheme {
    val now = Clock.System.now()
    MinutesScreen(
        minutes = sampleMinutes(now).first(),
        now = now,
        backLabel = stringResource(Res.string.meetings_title),
        backDescription = stringResource(Res.string.minutes_back_description),
        onBack = {},
        onToggleAction = {},
        onRename = {},
        onDelete = {},
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}
