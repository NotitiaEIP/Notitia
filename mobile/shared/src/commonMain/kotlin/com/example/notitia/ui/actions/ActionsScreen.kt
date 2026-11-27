package com.example.notitia.ui.actions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.notitia.meeting.ActionBoard
import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.DueGroup
import com.example.notitia.ui.components.CountBadge
import com.example.notitia.ui.components.DetailsLine
import com.example.notitia.ui.components.EmptyState
import com.example.notitia.ui.components.GroupTitle
import com.example.notitia.ui.components.NotitiaSegmentedControl
import com.example.notitia.ui.components.ScreenHeader
import com.example.notitia.ui.components.listMotion
import com.example.notitia.ui.components.today
import com.example.notitia.ui.minutes.sampleLists
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.actions_by_due
import notitia.shared.generated.resources.actions_by_person
import notitia.shared.generated.resources.actions_done
import notitia.shared.generated.resources.actions_empty
import notitia.shared.generated.resources.actions_late
import notitia.shared.generated.resources.actions_nothing_open
import notitia.shared.generated.resources.actions_open
import notitia.shared.generated.resources.actions_title
import notitia.shared.generated.resources.keyboard_arrow_down
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val HALF_TURN = 180f
private const val NOBODY = "nobody"

// The same actions, two ways to read them: what is due first, or who has what before a team meeting.
enum class ActionsView(val label: StringResource) {
    ByDue(Res.string.actions_by_due),
    ByPerson(Res.string.actions_by_person),
}

// A ticked action keeps its place a moment before it joins the done ones: onTick keeps it lingering, onLetGo ends
// that. Ticking several in a row keeps them all in place until the last moment ends, so no card moves under the
// finger. A swiped one is already off the screen: it is only set done. onCompleted gets the actions that just left the
// list, whichever way, so they can be taken back. openedAction is the action whose sheet is up, and sheetReveal how
// much of it shows. ownerChoices gives the first names of a meeting. view is kept by the caller, so it survives a
// change of tab.
@Composable
fun ActionsScreen(
    lists: ActionLists,
    lingering: Set<ActionId>,
    view: ActionsView,
    onViewChange: (ActionsView) -> Unit,
    openedAction: ActionId?,
    sheetReveal: SheetReveal,
    ownerChoices: (meetingId: String) -> List<String>,
    onTick: (ActionId, Boolean) -> Unit,
    onLetGo: (Set<ActionId>) -> Unit,
    onSetDone: (ActionId, Boolean) -> Unit,
    onSetOwner: (ActionId, String?) -> Unit,
    onSetDue: (ActionId, LocalDate?) -> Unit,
    onSetReminder: (ActionId, Boolean) -> Unit,
    onCompleted: (List<ActionId>) -> Unit,
    onOpenAction: (ActionId) -> Unit,
    onCloseAction: () -> Unit,
    onOpenMeeting: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = NotitiaTheme.motion
    val today = lists.today
    var showDone by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable(stateSaver = ExpandedSaver) { mutableStateOf(emptySet<String>()) }
    var nudging by rememberSaveable(stateSaver = NudgeTargetSaver) { mutableStateOf<NudgeTarget?>(null) }
    val timeZone = remember { TimeZone.currentSystemDefault() }
    // Written after each composition, read at the next one: not a state, so keeping it does not recompose.
    val personOrder = remember(view) { mutableListOf<String?>() }
    val people = if (view == ActionsView.ByPerson) lists.people.keptInOrder(personOrder) else emptyList()
    SideEffect {
        personOrder.clear()
        personOrder.addAll(people.map { it.key })
    }
    val completed by rememberUpdatedState(onCompleted)
    val letGo by rememberUpdatedState(onLetGo)
    val stillLingering by rememberUpdatedState(lingering)
    LaunchedEffect(lingering) {
        if (lingering.isEmpty()) return@LaunchedEffect
        delay(motion.ACTION_LINGER_MS.milliseconds)
        completed(lingering.toList())
        letGo(lingering)
    }
    // Leaving the list ends the moment at once, without a message: the actions are done already, as they were.
    DisposableEffect(Unit) {
        onDispose { letGo(stillLingering) }
    }
    fun toggle(tracked: TrackedAction) = onTick(tracked.id, !tracked.action.done)
    val actions = lists.all
    ActionList(
        lists = lists,
        people = people,
        view = view,
        onViewChange = onViewChange,
        today = today,
        showDone = showDone,
        onShowDoneChange = { showDone = it },
        expanded = expanded,
        onExpandedChange = { key, open -> expanded = if (open) expanded + key else expanded - key },
        onToggle = ::toggle,
        onSwipedDone = { tracked ->
            onSetDone(tracked.id, true)
            onCompleted(listOf(tracked.id))
        },
        onOpen = { onOpenAction(it.id) },
        nudgeOf = { tracked ->
            nudgeFor(tracked, timeZone)?.let { { nudging = NudgeTarget.Action(tracked.id) } }
        },
        personNudgeOf = { person ->
            nudgeFor(person, timeZone)?.let { person.key?.let { key -> { nudging = NudgeTarget.Person(key) } } }
        },
        onOpenMinutes = { onOpenMeeting(it.meeting.id) },
        modifier = modifier,
    )
    actions.firstOrNull { it.id == openedAction }?.let { tracked ->
        val owners = ownerChoices(tracked.meeting.id)
        ActionSheet(
            tracked = tracked,
            owners = owners,
            today = today,
            reveal = sheetReveal,
            onSetOwner = { name -> onSetOwner(tracked.id, name?.let { knownSpelling(it, owners) }) },
            onSetDue = { onSetDue(tracked.id, it) },
            onSetReminder = { onSetReminder(tracked.id, it) },
            onToggleDone = { toggle(tracked) },
            onOpenMinutes = { onOpenMeeting(tracked.meeting.id) },
            onNudge = nudgeFor(tracked, timeZone)?.let { { nudging = NudgeTarget.Action(tracked.id) } },
            onDismiss = onCloseAction,
        )
    }
    val nudge = when (val target = nudging) {
        null -> null
        is NudgeTarget.Action -> actions.firstOrNull { it.id == target.id }?.let { nudgeFor(it, timeZone) }
        is NudgeTarget.Person -> lists.people.firstOrNull { it.key == target.key }?.let { nudgeFor(it, timeZone) }
    }
    // Nothing left to nudge about, the action ticked meanwhile for one: the sheet goes.
    LaunchedEffect(nudging, nudge == null) {
        if (nudging != null && nudge == null) nudging = null
    }
    if (nudge != null) {
        key(nudging) { NudgeSheet(nudge, sheetReveal, onDismiss = { nudging = null }) }
    }
}

// Whom the message is about: one action, or every open action of a person.
private sealed interface NudgeTarget {
    data class Action(val id: ActionId) : NudgeTarget
    data class Person(val key: String) : NudgeTarget
}

// An Android Bundle keeps a list of strings, not a class of the app.
private val NudgeTargetSaver = Saver<NudgeTarget?, ArrayList<String>>(
    save = { target ->
        when (target) {
            null -> null
            is NudgeTarget.Action -> arrayListOf(target.id.meetingId, target.id.actionId)
            is NudgeTarget.Person -> arrayListOf(target.key)
        }
    },
    restore = { saved ->
        if (saved.size == 2) NudgeTarget.Action(ActionId(saved[0], saved[1])) else NudgeTarget.Person(saved[0])
    },
)

// An Android Bundle keeps a list of strings, not a set.
private val ExpandedSaver = Saver<Set<String>, ArrayList<String>>(save = { ArrayList(it) }, restore = { it.toSet() })

private fun PersonActions.expandKey(): String = key ?: NOBODY

@Composable
private fun ActionList(
    lists: ActionLists,
    people: List<PersonActions>,
    view: ActionsView,
    onViewChange: (ActionsView) -> Unit,
    today: LocalDate,
    showDone: Boolean,
    onShowDoneChange: (Boolean) -> Unit,
    expanded: Set<String>,
    onExpandedChange: (key: String, expanded: Boolean) -> Unit,
    onToggle: (TrackedAction) -> Unit,
    onSwipedDone: (TrackedAction) -> Unit,
    onOpen: (TrackedAction) -> Unit,
    nudgeOf: (TrackedAction) -> (() -> Unit)?,
    personNudgeOf: (PersonActions) -> (() -> Unit)?,
    onOpenMinutes: (TrackedAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NotitiaTheme.spacing
    val colors = NotitiaTheme.colors
    val empty = lists.toComplete.isEmpty() && lists.groups.isEmpty() && lists.done.isEmpty()

    @Composable
    fun Card(tracked: TrackedAction, modifier: Modifier) = ActionCard(
        tracked = tracked,
        today = today,
        onToggle = { onToggle(tracked) },
        onSwipedDone = { onSwipedDone(tracked) },
        onOpen = { onOpen(tracked) },
        onNudge = nudgeOf(tracked),
        onOpenMinutes = { onOpenMinutes(tracked) },
        modifier = modifier.padding(bottom = spacing.s),
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = spacing.l, end = spacing.l, bottom = spacing.xl),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                ScreenHeader(stringResource(Res.string.actions_title))
                if (!empty) {
                    OpenCount(lists)
                    NotitiaSegmentedControl(
                        options = ActionsView.entries.map { stringResource(it.label) },
                        selected = view.ordinal,
                        onSelect = { onViewChange(ActionsView.entries[it]) },
                        modifier = Modifier.padding(top = spacing.m),
                    )
                }
            }
        }
        if (empty) {
            item(key = "empty") {
                EmptyState(
                    message = stringResource(Res.string.actions_empty),
                    modifier = Modifier.padding(top = spacing.xxxl),
                )
            }
        }
        if (view == ActionsView.ByPerson) {
            itemsIndexed(people, key = { _, person -> "person/${person.expandKey()}" }) { index, person ->
                PersonCard(
                    person = person,
                    expanded = person.expandKey() in expanded,
                    onExpandedChange = { onExpandedChange(person.expandKey(), it) },
                    onToggle = onToggle,
                    onOpen = onOpen,
                    onNudge = personNudgeOf(person),
                    modifier = listMotion(swipedOff = true).padding(top = if (index == 0) spacing.l else spacing.s),
                )
            }
            return@LazyColumn
        }
        if (lists.toComplete.isNotEmpty()) {
            item(key = "to-complete") {
                ToCompleteCard(lists.toComplete, onOpen, listMotion(swipedOff = true).padding(top = spacing.l))
            }
        }
        lists.groups.forEach { (group, inGroup) ->
            item(key = group.name) {
                GroupTitle(
                    text = stringResource(group.title),
                    modifier = listMotion(swipedOff = true).padding(top = spacing.xl, bottom = spacing.s),
                    color = if (group == DueGroup.Late) colors.red else colors.textTertiary,
                )
            }
            items(inGroup, key = { it.id.key }) { Card(it, listMotion(swipedOff = true)) }
        }
        if (lists.done.isNotEmpty()) {
            item(key = "done") {
                DoneHeader(
                    count = lists.done.size,
                    expanded = showDone,
                    onExpandedChange = onShowDoneChange,
                    modifier = listMotion(swipedOff = true).padding(top = spacing.xl, bottom = spacing.s),
                )
            }
            // One block that unfolds and folds back as a whole, so the list follows its height instead of jumping.
            // Its cards are composed apart from the groups: one joins it fresh, never with the swipe it left with.
            item(key = "done-list") {
                AnimatedVisibility(
                    visible = showDone,
                    modifier = listMotion(swipedOff = true),
                    enter = foldEnter(reducedMotion()),
                    exit = foldExit(reducedMotion()),
                ) {
                    Column {
                        lists.done.forEach { tracked -> key(tracked.id.key) { Card(tracked, Modifier) } }
                    }
                }
            }
        }
    }
}

// The done ones unfold from the top as Material's expanding lists do; with reduced motion, only the fades.
@Composable
internal fun foldEnter(reduced: Boolean): EnterTransition {
    val motion = NotitiaTheme.motion
    val fade = fadeIn(motion.standard())
    return if (reduced) fade else fade + expandVertically(motion.standard(), expandFrom = Alignment.Top)
}

@Composable
internal fun foldExit(reduced: Boolean): ExitTransition {
    val motion = NotitiaTheme.motion
    val fade = fadeOut(motion.quick())
    return if (reduced) fade else fade + shrinkVertically(motion.standard(), shrinkTowards = Alignment.Top)
}

// "7 en cours · 2 en retard": what is left, and what is late in red.
@Composable
private fun OpenCount(lists: ActionLists) {
    if (lists.open == 0) {
        DetailsLine(listOf(stringResource(Res.string.actions_nothing_open)))
    } else {
        DetailsLine(
            parts = listOf(stringResource(Res.string.actions_open, lists.open)),
            alert = lists.late.takeIf { it > 0 }?.let { stringResource(Res.string.actions_late, it) },
        )
    }
}

// Folded by default, as in Reminders: what is done no longer needs attention.
@Composable
private fun DoneHeader(count: Int, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, modifier: Modifier) {
    val colors = NotitiaTheme.colors
    val rotation by animateFloatAsState(
        targetValue = if (expanded) HALF_TURN else 0f,
        animationSpec = if (reducedMotion()) snap() else NotitiaTheme.motion.standard(),
        label = "chevron",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NotitiaTheme.sizes.touchTarget)
            .clickable { onExpandedChange(!expanded) }
            .semantics(mergeDescendants = true) {
                heading()
                if (expanded) collapse { onExpandedChange(false); true } else expand { onExpandedChange(true); true }
            },
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.actions_done),
            style = NotitiaTheme.typography.labelSmall,
            color = colors.textTertiary,
        )
        CountBadge(count)
        Spacer(Modifier.weight(1f))
        Icon(
            painter = painterResource(Res.drawable.keyboard_arrow_down),
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(NotitiaTheme.sizes.icon).rotate(rotation),
        )
    }
}

@Composable
private fun ActionListPreview(
    lists: ActionLists,
    showDone: Boolean,
    view: ActionsView = ActionsView.ByDue,
    expanded: Set<String> = emptySet(),
) = NotitiaTheme {
    ActionList(
        lists = lists,
        people = lists.people,
        view = view,
        onViewChange = {},
        today = lists.today,
        showDone = showDone,
        onShowDoneChange = {},
        expanded = expanded,
        onExpandedChange = { _, _ -> },
        onToggle = {},
        onSwipedDone = {},
        onOpen = {},
        nudgeOf = { tracked -> tracked.action.owner?.let { {} } },
        personNudgeOf = { person -> person.name?.let { {} } },
        onOpenMinutes = {},
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}

@NotitiaPreviews
@Composable
private fun ActionsByDuePreview() = ActionListPreview(sampleLists(Clock.System.now()), showDone = false)

@NotitiaPreviews
@Composable
private fun ActionsWithDonePreview() = ActionListPreview(sampleLists(Clock.System.now()), showDone = true)

@NotitiaPreviews
@Composable
private fun ActionsByPersonPreview() {
    val lists = sampleLists(Clock.System.now())
    val first = lists.people.firstOrNull()?.expandKey()
    ActionListPreview(lists, showDone = false, view = ActionsView.ByPerson, expanded = setOfNotNull(first))
}

// Every sample ticked: what the board would hold, written out.
@NotitiaPreviews
@Composable
private fun ActionsAllDonePreview() {
    val lists = sampleLists(Clock.System.now())
    val done = lists.all.map { it.copy(action = it.action.copy(done = true), daysLate = null) }
    val ticked = lists.copy(toComplete = emptyList(), groups = emptyList(), done = done, open = 0, late = 0)
    ActionListPreview(ticked, showDone = false)
}

@NotitiaPreviews
@Composable
private fun ActionsEmptyPreview() = ActionListPreview(ActionBoard.empty(today()).lists(emptyList()), showDone = false)
