@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.notitia.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import com.example.notitia.ui.components.CardChevron
import com.example.notitia.ui.components.ContextMenu
import com.example.notitia.ui.components.DetailsLine
import com.example.notitia.ui.components.NotitiaButton
import com.example.notitia.ui.components.SpeakerAvatar
import com.example.notitia.ui.components.StrikeText
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.clockTime
import com.example.notitia.ui.meetings.meetingWhenLabel
import com.example.notitia.ui.meetings.sampleMeetings
import com.example.notitia.ui.meetings.shortDateLabel
import com.example.notitia.ui.minutes.ActionSource
import com.example.notitia.ui.minutes.sampleMinutes
import com.example.notitia.ui.theme.MotionStyle
import com.example.notitia.ui.theme.NotitiaMotion
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.motionStyle
import com.example.notitia.ui.theme.reducedMotion
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.action_choose
import notitia.shared.generated.resources.action_due_next_week
import notitia.shared.generated.resources.action_due_none
import notitia.shared.generated.resources.action_due_pick
import notitia.shared.generated.resources.action_mark_done
import notitia.shared.generated.resources.action_mark_todo
import notitia.shared.generated.resources.action_no_due
import notitia.shared.generated.resources.action_nobody
import notitia.shared.generated.resources.action_open_minutes
import notitia.shared.generated.resources.action_other_owner
import notitia.shared.generated.resources.action_other_owner_label
import notitia.shared.generated.resources.action_other_owner_title
import notitia.shared.generated.resources.action_quote
import notitia.shared.generated.resources.action_sheet_due
import notitia.shared.generated.resources.action_sheet_owner
import notitia.shared.generated.resources.calendar_today
import notitia.shared.generated.resources.chat_bubble
import notitia.shared.generated.resources.cancel
import notitia.shared.generated.resources.check
import notitia.shared.generated.resources.description
import notitia.shared.generated.resources.group_today
import notitia.shared.generated.resources.group_tomorrow
import notitia.shared.generated.resources.keyboard_arrow_down
import notitia.shared.generated.resources.nudge_title
import notitia.shared.generated.resources.person
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val DAYS_IN_A_WEEK = 7

// How much of the action's sheet shows, from 0 to 1. Read while drawing, so the screen behind follows the sheet
// frame by frame, under the finger too. Material's own veil only fades in or out once the sheet has settled.
@Stable
class SheetReveal {
    internal var sheet by mutableStateOf<SheetState?>(null)
    internal var fullHeight by mutableIntStateOf(0)
    internal var handleHeight by mutableIntStateOf(0)
    internal var contentHeight by mutableIntStateOf(0)

    // Material hides the sheet at the full height of its window and opens it at that height minus its own.
    fun fraction(): Float {
        val state = sheet ?: return 0f
        val height = handleHeight + contentHeight
        if (height == 0 || fullHeight == 0) return 0f
        val offset = try {
            state.requireOffset()
        } catch (_: IllegalStateException) {
            return 0f
        }
        return ((fullHeight - offset) / height).coerceIn(0f, 1f)
    }
}

// The veil follows the sheet on both systems. On iPhone the screen also steps back like a card on a stack, as
// under a page sheet in UIKit; Android's sheets leave the screen in place. Reduced motion keeps it in place too.
@Composable
fun RecedesUnderSheet(reveal: SheetReveal, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = NotitiaTheme.colors
    val stacks = motionStyle == MotionStyle.Cupertino && !reducedMotion()
    val radius = NotitiaTheme.sizes.sheetRadius
    val density = LocalDensity.current
    val drop = WindowInsets.statusBars.getTop(density) + with(density) { NotitiaTheme.spacing.s.toPx() }
    Box(
        modifier
            .then(if (stacks) Modifier.background(colors.stack) else Modifier)
            .drawWithContent {
                drawContent()
                val shown = reveal.fraction()
                if (shown > 0f) drawRect(colors.dim.copy(alpha = colors.dim.alpha * shown))
            },
    ) {
        Box(
            if (!stacks) {
                Modifier
            } else {
                Modifier.graphicsLayer {
                    val shown = reveal.fraction()
                    val scale = 1f - (1f - NotitiaMotion.STACK_SCALE) * shown
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0f)
                    translationY = drop * shown
                    shape = RoundedCornerShape(radius * shown)
                    clip = shown > 0f
                }
            },
        ) {
            content()
        }
    }
}

// Material's sheet on both systems: it brings the handle, the swipe down, the tap outside and the back gesture.
// Ticking and opening the minutes first let the sheet slide away, so it never vanishes at once.
@Composable
fun ActionSheet(
    tracked: TrackedAction,
    owners: List<String>,
    today: LocalDate,
    reveal: SheetReveal,
    onSetOwner: (String?) -> Unit,
    onSetDue: (LocalDate?) -> Unit,
    onToggleDone: () -> Unit,
    onOpenMinutes: () -> Unit,
    onDismiss: () -> Unit,
    onNudge: (() -> Unit)? = null,
) {
    val sheetState = rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    fun closeThen(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            then()
            onDismiss()
        }
    }
    RevealingSheet(sheetState, reveal, onDismiss) { measured ->
        ActionSheetContent(
            tracked = tracked,
            owners = owners,
            today = today,
            onSetOwner = onSetOwner,
            onSetDue = onSetDue,
            onToggleDone = { closeThen(onToggleDone) },
            onOpenMinutes = { closeThen(onOpenMinutes) },
            onNudge = onNudge?.let { nudge -> { closeThen(nudge) } },
            modifier = measured.windowInsetsPadding(WindowInsets.navigationBars),
        )
    }
}

// Material's sheet as the app draws it: its handle and colours, and the screen behind following it through reveal.
// content gets the modifier that measures it, for the veil.
@Composable
internal fun RevealingSheet(
    sheetState: SheetState,
    reveal: SheetReveal,
    onDismiss: () -> Unit,
    content: @Composable (measured: Modifier) -> Unit,
) {
    val colors = NotitiaTheme.colors
    DisposableEffect(sheetState) {
        reveal.sheet = sheetState
        onDispose { if (reveal.sheet === sheetState) reveal.sheet = null }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = NotitiaTheme.shapes.sheet,
        containerColor = colors.surfaceRaised,
        contentColor = colors.text,
        // Drawn by RecedesUnderSheet instead, where it follows the sheet. Transparent, it still closes on a tap.
        scrimColor = Color.Transparent,
        // The content pads itself, so its measured height is the whole sheet's.
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = {
            Box(Modifier.onSizeChanged { reveal.handleHeight = it.height }) {
                BottomSheetDefaults.DragHandle(
                    width = NotitiaTheme.sizes.handleWidth,
                    height = NotitiaTheme.sizes.handleHeight,
                    color = colors.textTertiary,
                )
            }
        },
    ) {
        content(
            Modifier.onGloballyPositioned {
                reveal.fullHeight = it.findRootCoordinates().size.height
                reveal.contentHeight = it.size.height
            },
        )
    }
}

@Composable
private fun ActionSheetContent(
    tracked: TrackedAction,
    owners: List<String>,
    today: LocalDate,
    onSetOwner: (String?) -> Unit,
    onSetDue: (LocalDate?) -> Unit,
    onToggleDone: () -> Unit,
    onOpenMinutes: () -> Unit,
    onNudge: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = NotitiaTheme.spacing
    val action = tracked.action
    val meetingDay = tracked.meeting.startedAt.toLocalDateTime(TimeZone.currentSystemDefault()).date
    var typingOwner by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = spacing.xl, top = spacing.s, end = spacing.xl, bottom = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.xl),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
            StrikeText(
                text = action.text,
                struck = action.done,
                style = NotitiaTheme.typography.title2,
                modifier = Modifier.weight(1f),
            )
            if (onNudge != null && action.owner != null) NudgeButton(action.owner, onNudge)
        }
        Column(Modifier.clip(NotitiaTheme.shapes.card).background(NotitiaTheme.colors.surface)) {
            OwnerRow(tracked, owners, onSetOwner, onTypeOwner = { typingOwner = true })
            HorizontalDivider(color = NotitiaTheme.colors.divider)
            DueRow(tracked, today, onSetDue, onPickDate = { pickingDate = true })
        }
        action.source?.let { Quote(it, tracked.meeting) }
        MeetingRow(tracked.meeting, onOpenMinutes)
        NotitiaButton(
            text = stringResource(if (action.done) Res.string.action_mark_todo else Res.string.action_mark_done),
            onClick = onToggleDone,
            icon = if (action.done) null else painterResource(Res.drawable.check),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (typingOwner) {
        OwnerDialog(
            onChoose = { name ->
                onSetOwner(name)
                typingOwner = false
            },
            onDismiss = { typingOwner = false },
        )
    }
    if (pickingDate) {
        DueDateDialog(
            due = action.due,
            range = dueDateRange(meetingDay, today),
            onChoose = { date ->
                onSetDue(date)
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
        )
    }
}

@Composable
private fun OwnerRow(
    tracked: TrackedAction,
    owners: List<String>,
    onSetOwner: (String?) -> Unit,
    onTypeOwner: () -> Unit,
) {
    val owner = tracked.action.owner
    var menu by remember { mutableStateOf(false) }
    fun choose(then: () -> Unit) {
        menu = false
        then()
    }
    PropertyRow(
        icon = Res.drawable.person,
        label = stringResource(Res.string.action_sheet_owner),
        onClick = { menu = true },
        value = {
            if (owner == null) {
                Text(stringResource(Res.string.action_nobody), style = NotitiaTheme.typography.body, color = NotitiaTheme.colors.textTertiary)
            } else {
                SpeakerAvatar(owner, tracked.speaker)
                Text(owner, style = NotitiaTheme.typography.body, color = NotitiaTheme.colors.textSecondary)
            }
        },
    ) {
        ContextMenu(expanded = menu, onDismissRequest = { menu = false }) {
            owners.forEach { name -> MenuChoice(name, selected = owner == name) { choose { onSetOwner(name) } } }
            HorizontalDivider(color = NotitiaTheme.colors.divider)
            MenuChoice(stringResource(Res.string.action_other_owner), selected = false) { choose(onTypeOwner) }
            MenuChoice(stringResource(Res.string.action_nobody), selected = owner == null) { choose { onSetOwner(null) } }
        }
    }
}

@Composable
private fun DueRow(tracked: TrackedAction, today: LocalDate, onSetDue: (LocalDate?) -> Unit, onPickDate: () -> Unit) {
    val action = tracked.action
    val due = action.due
    val late = due != null && !action.done && dueGroup(due, today) == DueGroup.Late
    var menu by remember { mutableStateOf(false) }
    fun choose(then: () -> Unit) {
        menu = false
        then()
    }
    val quick = listOf(
        stringResource(Res.string.group_today) to today,
        stringResource(Res.string.group_tomorrow) to today.plus(DatePeriod(days = 1)),
        stringResource(Res.string.action_due_next_week) to today.plus(DatePeriod(days = DAYS_IN_A_WEEK)),
    )
    PropertyRow(
        icon = Res.drawable.calendar_today,
        label = stringResource(Res.string.action_sheet_due),
        onClick = { menu = true },
        value = {
            Text(
                text = due?.let { dueLabel(it, today) } ?: stringResource(Res.string.action_due_none),
                style = NotitiaTheme.typography.body,
                color = when {
                    late -> NotitiaTheme.colors.red
                    due == null -> NotitiaTheme.colors.textTertiary
                    else -> NotitiaTheme.colors.textSecondary
                },
            )
        },
    ) {
        ContextMenu(expanded = menu, onDismissRequest = { menu = false }) {
            quick.forEach { (label, date) -> MenuChoice(label, selected = due == date) { choose { onSetDue(date) } } }
            HorizontalDivider(color = NotitiaTheme.colors.divider)
            val picked = due != null && quick.none { it.second == due }
            MenuChoice(stringResource(Res.string.action_due_pick), selected = picked) { choose(onPickDate) }
            MenuChoice(stringResource(Res.string.action_no_due), selected = due == null) { choose { onSetDue(null) } }
        }
    }
}

@Composable
private fun dueLabel(due: LocalDate, today: LocalDate): String = when (dueGroup(due, today)) {
    DueGroup.Today -> stringResource(Res.string.group_today)
    DueGroup.Tomorrow -> stringResource(Res.string.group_tomorrow)
    else -> shortDateLabel(due)
}

// An icon at the end of the title, as in the toolbar of an iOS sheet: a nudge is never a big button. Its glyph lines
// up with the edge of the content, its target reaches into the margin.
@Composable
private fun NudgeButton(owner: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(NotitiaTheme.sizes.touchTarget)
            .offset(x = NotitiaTheme.spacing.m, y = -NotitiaTheme.spacing.s),
    ) {
        Icon(
            painter = painterResource(Res.drawable.chat_bubble),
            contentDescription = stringResource(Res.string.nudge_title, owner),
            tint = NotitiaTheme.colors.textSecondary,
            modifier = Modifier.size(NotitiaTheme.sizes.icon),
        )
    }
}

// A line of a grouped list, as in the settings of both systems: what it is on the left, its value on the right.
// Touching it opens its menu, which hangs from the row.
@Composable
private fun PropertyRow(
    icon: DrawableResource,
    label: String,
    onClick: () -> Unit,
    value: @Composable () -> Unit,
    menu: @Composable () -> Unit,
) {
    val colors = NotitiaTheme.colors
    val spacing = NotitiaTheme.spacing
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NotitiaTheme.sizes.field)
                .clickable(onClick = onClick)
                .padding(horizontal = spacing.l, vertical = spacing.m),
            horizontalArrangement = Arrangement.spacedBy(spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(NotitiaTheme.sizes.icon),
            )
            Text(text = label, style = NotitiaTheme.typography.body, modifier = Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                value()
                Icon(
                    painter = painterResource(Res.drawable.keyboard_arrow_down),
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(NotitiaTheme.sizes.iconSmall),
                )
            }
        }
        menu()
    }
}

// The chosen one carries a check in front, as in the menus of both systems.
@Composable
private fun MenuChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = NotitiaTheme.colors
    DropdownMenuItem(
        text = { Text(text = text, style = NotitiaTheme.typography.body, color = colors.text) },
        leadingIcon = {
            Box(Modifier.size(NotitiaTheme.sizes.iconInButton)) {
                if (selected) {
                    Icon(
                        painter = painterResource(Res.drawable.check),
                        contentDescription = null,
                        tint = colors.accentContent,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
        onClick = onClick,
        modifier = Modifier.semantics { this.selected = selected },
    )
}

// The sentence as transcribed, under a violet bar, then who said it and at what time.
@Composable
private fun Quote(source: ActionSource, meeting: MeetingListItem) {
    val spacing = NotitiaTheme.spacing
    val said = (meeting.startedAt + source.at).toLocalDateTime(TimeZone.currentSystemDefault()).time
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        Box(
            Modifier
                .width(NotitiaTheme.sizes.quoteBar)
                .fillMaxHeight()
                .background(NotitiaTheme.colors.accent, NotitiaTheme.shapes.full),
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = stringResource(Res.string.action_quote, source.quote),
                style = NotitiaTheme.typography.body,
                color = NotitiaTheme.colors.textSecondary,
            )
            DetailsLine(listOfNotNull(source.speaker, clockTime(said)), style = NotitiaTheme.typography.caption)
        }
    }
}

// The meeting the action was agreed in; touching it opens its minutes.
@Composable
private fun MeetingRow(meeting: MeetingListItem, onClick: () -> Unit) {
    val now = remember { Clock.System.now() }
    val spacing = NotitiaTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NotitiaTheme.shapes.card)
            .background(NotitiaTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.l, vertical = spacing.m),
        horizontalArrangement = Arrangement.spacedBy(spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.description),
            contentDescription = null,
            tint = NotitiaTheme.colors.textSecondary,
            modifier = Modifier.size(NotitiaTheme.sizes.icon),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                text = meeting.title,
                style = NotitiaTheme.typography.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DetailsLine(
                listOf(stringResource(Res.string.action_open_minutes), meetingWhenLabel(meeting.startedAt, now)),
                style = NotitiaTheme.typography.caption,
            )
        }
        CardChevron()
    }
}

@Composable
private fun OwnerDialog(onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = NotitiaTheme.colors
    val field = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val buttons = ButtonDefaults.textButtonColors(contentColor = colors.accentContent)
    val canChoose = field.text.isNotBlank()
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.action_other_owner_title), style = NotitiaTheme.typography.title3) },
        text = {
            OutlinedTextField(
                state = field,
                label = { Text(stringResource(Res.string.action_other_owner_label)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = NotitiaTheme.typography.body,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                onKeyboardAction = { if (canChoose) onChoose(field.text.toString()) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onChoose(field.text.toString()) }, enabled = canChoose, colors = buttons) {
                Text(stringResource(Res.string.action_choose), style = NotitiaTheme.typography.label)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = buttons) {
                Text(stringResource(Res.string.cancel), style = NotitiaTheme.typography.label)
            }
        },
    )
}

// The calendar only: Material's typed mode garbled its digits when switching back. Days out of range are greyed,
// and a date the model gave out of range shows but cannot be confirmed.
@Composable
private fun DueDateDialog(
    due: LocalDate?,
    range: ClosedRange<LocalDate>,
    onChoose: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val buttons = ButtonDefaults.textButtonColors(contentColor = NotitiaTheme.colors.accentContent)
    val years = range.start.year..range.endInclusive.year
    val selectable = remember(range) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcDate(utcTimeMillis) in range
            override fun isSelectableYear(year: Int) = year in years
        }
    }
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = due?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds(),
        yearRange = years,
        selectableDates = selectable,
    )
    val picked = picker.selectedDateMillis?.let(::utcDate)?.takeIf { it in range }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { picked?.let(onChoose) }, enabled = picked != null, colors = buttons) {
                Text(stringResource(Res.string.action_choose), style = NotitiaTheme.typography.label)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = buttons) {
                Text(stringResource(Res.string.cancel), style = NotitiaTheme.typography.label)
            }
        },
    ) {
        DatePicker(state = picker, showModeToggle = false)
    }
}

// The picker counts in milliseconds at midnight UTC, whatever the phone's time zone.
private fun utcDate(millis: Long): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date

@Composable
private fun ActionSheetPreview(index: Int) = NotitiaTheme {
    val now = Clock.System.now()
    val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
    val minutes = sampleMinutes(sampleMeetings(now).first(), now)
    val tracked = ActionsState().tracked(listOf(minutes))[index]
    Box(Modifier.background(NotitiaTheme.colors.surfaceRaised)) {
        ActionSheetContent(
            tracked = tracked,
            owners = ownerChoices(minutes),
            today = today,
            onSetOwner = {},
            onSetDue = {},
            onToggleDone = {},
            onOpenMinutes = {},
            onNudge = {},
        )
    }
}

@NotitiaPreviews
@Composable
private fun ActionSheetWithSentencePreview() = ActionSheetPreview(index = 0)

@NotitiaPreviews
@Composable
private fun ActionSheetWithoutSentencePreview() = ActionSheetPreview(index = 1)

@NotitiaPreviews
@Composable
private fun ActionSheetDonePreview() = ActionSheetPreview(index = 2)
