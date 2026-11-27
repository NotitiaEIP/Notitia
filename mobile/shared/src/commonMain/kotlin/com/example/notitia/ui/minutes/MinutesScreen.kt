package com.example.notitia.ui.minutes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.notitia.meeting.Abilities
import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.TranscriptLine
import com.example.notitia.ui.components.ActionRow
import com.example.notitia.ui.components.BackButton
import com.example.notitia.ui.components.ContextMenu
import com.example.notitia.ui.components.ContextMenuItem
import com.example.notitia.ui.components.DestructiveConfirmation
import com.example.notitia.ui.components.DetailsLine
import com.example.notitia.ui.components.NotitiaButton
import com.example.notitia.ui.components.NotitiaSegmentedControl
import com.example.notitia.ui.components.SectionHeader
import com.example.notitia.ui.components.SpeakerChip
import com.example.notitia.ui.components.rememberContextMenuState
import com.example.notitia.ui.components.rememberTextShare
import com.example.notitia.ui.components.rememberToday
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.sampleMinutes
import com.example.notitia.ui.meetings.lengthLabel
import com.example.notitia.ui.meetings.meetingDeletion
import com.example.notitia.ui.meetings.meetingWhenLabel
import com.example.notitia.ui.meetings.shortDateLabel
import com.example.notitia.ui.recording.timerText
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import com.example.notitia.ui.theme.shareIcon
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.delete
import notitia.shared.generated.resources.edit
import notitia.shared.generated.resources.meeting_delete
import notitia.shared.generated.resources.meeting_rename
import notitia.shared.generated.resources.meetings_title
import notitia.shared.generated.resources.minutes_actions
import notitia.shared.generated.resources.minutes_audio_erased
import notitia.shared.generated.resources.minutes_back_description
import notitia.shared.generated.resources.minutes_decisions
import notitia.shared.generated.resources.minutes_more
import notitia.shared.generated.resources.minutes_no_actions
import notitia.shared.generated.resources.minutes_no_decisions
import notitia.shared.generated.resources.minutes_no_summary
import notitia.shared.generated.resources.minutes_share
import notitia.shared.generated.resources.minutes_summary
import notitia.shared.generated.resources.minutes_summary_more
import notitia.shared.generated.resources.minutes_summary_unfold
import notitia.shared.generated.resources.minutes_tab_minutes
import notitia.shared.generated.resources.minutes_tab_transcript
import notitia.shared.generated.resources.more_horiz
import notitia.shared.generated.resources.participants
import notitia.shared.generated.resources.transcript_empty
import notitia.shared.generated.resources.transcript_failed
import notitia.shared.generated.resources.transcript_unreadable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val SUMMARY_FOLDED_LINES = 3

@Composable
fun MinutesRoute(
    meeting: Meeting,
    item: MeetingListItem,
    abilities: Abilities,
    transcript: suspend () -> List<TranscriptLine>?,
    onSetDone: (ActionId, Boolean) -> Unit,
    onRewrite: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    backLabel: String,
    backDescription: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = rememberToday()
    val now = remember(today) { Clock.System.now() }
    val minutes = meeting.toMinutes(item)
    val share = rememberTextShare()
    val shareWords = rememberMinutesShareWords()
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        onBackCompleted = onBack,
    )
    // The lines are read only for minutes written empty, to tell an engine that failed from a silent meeting.
    val everyPassageFailed by produceState(false, meeting.id, meeting.minutes) {
        val written = (meeting.minutes as? MinutesState.Written)?.minutes
        value = written?.isEmpty() == true && transcript()?.let(::everyPassageFailed) == true
    }
    val notice = meeting.minutesNotice(abilities, everyPassageFailed)
    MinutesArrival(meeting)
    MinutesScreen(
        minutes = minutes,
        notice = notice,
        now = now,
        backLabel = backLabel,
        backDescription = backDescription,
        onBack = onBack,
        onToggleAction = { index -> minutes.actions[index].let { onSetDone(ActionId(meeting.id, it.id), !it.done) } },
        onRename = onRename,
        onDelete = onDelete,
        onShare = if (notice == null) {
            { share(minutesShareText(minutes, shareWords, TimeZone.currentSystemDefault())) {} }
        } else {
            null
        },
        onRewrite = onRewrite,
        transcript = transcript,
        revision = meeting.id to meeting.modifiedAt,
        modifier = modifier,
    )
}

// The success vibration of DESIGN.md, once minutes awaited on this screen are written: not for minutes opened ready.
@Composable
private fun MinutesArrival(meeting: Meeting) {
    val haptics = LocalHapticFeedback.current
    var awaited by remember(meeting.id) { mutableStateOf(false) }
    LaunchedEffect(meeting.id, meeting.minutes) {
        if (awaited && meeting.minutes is MinutesState.Written) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        awaited = meeting.minutes == MinutesState.Writing
    }
}

// notice replaces the minutes when there is nothing to read of them, and onShare is null then: there is nothing to
// send. The notice's button, when it has one, takes the place of sharing. transcript reads the lines, null when it
// cannot: asked when their tab is shown, and again when revision changes.
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
    onShare: (() -> Unit)?,
    notice: MinutesNotice? = null,
    onRewrite: () -> Unit = {},
    transcript: suspend () -> List<TranscriptLine>? = { emptyList() },
    revision: Any? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = NotitiaTheme.spacing
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Ticking an action on the other tab reads nothing; a line just transcribed shows as the meeting changes.
    val lines = if (tab != 0) readTranscript(transcript, revision) else Transcript.Reading
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
        // Lazy for the transcript: an hour of meeting is hundreds of lines, and only those on screen are laid out.
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = spacing.l, end = spacing.l, bottom = spacing.xl),
        ) {
            item {
                Column {
                    MeetingTitle(title = meeting.title, onRename = onRename, editRequests = renameRequests)
                    DetailsLine(
                        parts = listOf(
                            meetingWhenLabel(meeting.startedAt, now),
                            lengthLabel(meeting.duration),
                            pluralStringResource(Res.plurals.participants, meeting.participants, meeting.participants),
                        ),
                        modifier = Modifier.padding(top = spacing.xs),
                    )
                    minutes.audioErasedAt?.let { erased ->
                        val day = erased.toLocalDateTime(TimeZone.currentSystemDefault()).date
                        DetailsLine(
                            parts = listOf(stringResource(Res.string.minutes_audio_erased, shortDateLabel(day))),
                            modifier = Modifier.padding(top = spacing.xs),
                        )
                    }
                    if (minutes.speakers.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(top = spacing.l),
                            horizontalArrangement = Arrangement.spacedBy(spacing.s),
                            verticalArrangement = Arrangement.spacedBy(spacing.s),
                        ) {
                            val people = minutes.people
                            minutes.speakers.forEach { name -> SpeakerChip(name, people.of(name)) }
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
                }
            }
            when {
                tab != 0 -> transcriptItems(lines)
                notice != null -> item { Notice(stringResource(notice.text), Modifier.padding(top = spacing.xxl)) }
                else -> item { Column { MinutesContent(minutes, onToggleAction) } }
            }
        }
        val rewrite = notice?.rewrite
        when {
            onShare != null -> BottomAction(stringResource(Res.string.minutes_share), onShare, painterResource(shareIcon))
            rewrite != null -> BottomAction(stringResource(rewrite), onRewrite)
        }
    }
    DestructiveConfirmation(
        destruction = if (confirmingDelete) meetingDeletion(meeting.title) else null,
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
        title = stringResource(Res.string.minutes_summary),
        modifier = Modifier.padding(top = spacing.xxl, bottom = spacing.s),
    )
    if (minutes.summary.isEmpty()) {
        Notice(stringResource(Res.string.minutes_no_summary))
    } else {
        Summary(minutes.summary)
    }

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
    val people = minutes.people
    minutes.actions.forEachIndexed { index, action ->
        ActionRow(
            action = action,
            onToggle = { onToggleAction(index) },
            speaker = action.owner?.let(people::of),
        )
    }
}

// Folded as an app's description in the App Store: three lines, then « plus » at the end of the last one, the text
// fading out under it. The context in a glance, the decisions and actions on the first screen however long the
// summary. A touch anywhere on it unfolds it for good.
@Composable
private fun Summary(text: String) {
    val colors = NotitiaTheme.colors
    val style = NotitiaTheme.typography.body
    var unfolded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    val folded = overflows && !unfolded
    val unfold = stringResource(Res.string.minutes_summary_unfold)
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (reducedMotion()) Modifier else Modifier.animateContentSize(NotitiaTheme.motion.standard()))
            .then(
                if (folded) {
                    Modifier.clickable(interactionSource = null, indication = null, onClickLabel = unfold, role = Role.Button) {
                        unfolded = true
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Text(
            text = text,
            style = style,
            maxLines = if (unfolded) Int.MAX_VALUE else SUMMARY_FOLDED_LINES,
            overflow = TextOverflow.Clip,
            onTextLayout = { if (!unfolded) overflows = it.hasVisualOverflow },
            modifier = Modifier.fillMaxWidth(),
        )
        if (folded) {
            // Read by screen readers through the click label: they get the whole text, and nothing to say "plus" for.
            Row(Modifier.clearAndSetSemantics {}) {
                Box(
                    Modifier
                        .width(NotitiaTheme.sizes.summaryFade)
                        .height(with(LocalDensity.current) { style.lineHeight.toDp() })
                        .background(Brush.horizontalGradient(listOf(colors.background.copy(alpha = 0f), colors.background))),
                )
                Text(
                    text = stringResource(Res.string.minutes_summary_more),
                    style = style,
                    color = colors.accentContent,
                    modifier = Modifier.background(colors.background).padding(start = NotitiaTheme.spacing.xxs),
                )
            }
        }
    }
}

// The one main action of the screen, at its foot.
@Composable
private fun BottomAction(text: String, onClick: () -> Unit, icon: Painter? = null) {
    HorizontalDivider(color = NotitiaTheme.colors.divider)
    NotitiaButton(
        text = text,
        onClick = onClick,
        icon = icon,
        modifier = Modifier.fillMaxWidth().padding(NotitiaTheme.spacing.l),
    )
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

private sealed interface Transcript {
    // Nothing shows for that instant: « no word » would be wrong, and gone at once.
    data object Reading : Transcript

    data object Unreadable : Transcript

    data class Read(val lines: List<TranscriptLine>) : Transcript
}

// The lines already shown stay until the new ones are read.
@Composable
private fun readTranscript(read: suspend () -> List<TranscriptLine>?, revision: Any?): Transcript {
    val latest by rememberUpdatedState(read)
    return produceState<Transcript>(Transcript.Reading, revision) {
        value = latest()?.let(Transcript::Read) ?: Transcript.Unreadable
    }.value
}

private fun LazyListScope.transcriptItems(transcript: Transcript) {
    fun notice(text: StringResource) = item { Notice(stringResource(text), Modifier.padding(top = NotitiaTheme.spacing.xl)) }
    when (transcript) {
        Transcript.Reading -> Unit
        Transcript.Unreadable -> notice(Res.string.transcript_unreadable)
        is Transcript.Read -> {
            if (transcript.lines.isEmpty()) notice(Res.string.transcript_empty)
            itemsIndexed(transcript.lines) { index, line ->
                val spacing = NotitiaTheme.spacing
                TranscriptLineItem(line, Modifier.padding(top = if (index == 0) spacing.xl else spacing.m))
            }
        }
    }
}

@Composable
private fun TranscriptLineItem(line: TranscriptLine, modifier: Modifier = Modifier) {
    val colors = NotitiaTheme.colors
    val typography = NotitiaTheme.typography
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.xxs),
    ) {
        Text(text = timerText(line.at), style = typography.caption, color = colors.textSecondary)
        Text(
            text = line.text ?: stringResource(Res.string.transcript_failed),
            style = typography.body,
            color = if (line.text == null) colors.textSecondary else colors.text,
        )
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
        onShare = {},
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}
