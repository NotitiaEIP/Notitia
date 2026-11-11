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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.notitia.meeting.ActionId
import com.example.notitia.ui.components.CountBadge
import com.example.notitia.ui.components.DetailsLine
import com.example.notitia.ui.components.EmptyState
import com.example.notitia.ui.components.GroupTitle
import com.example.notitia.ui.components.NotitiaSegmentedControl
import com.example.notitia.ui.components.ScreenHeader
import com.example.notitia.ui.meetings.sampleMinutes
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.actions_by_due
import notitia.shared.generated.resources.actions_by_person
import notitia.shared.generated.resources.actions_done
import notitia.shared.generated.resources.actions_empty
import notitia.shared.generated.resources.actions_empty_hint
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

// A ticked action keeps its place a moment before it joins the done ones. Ticking several in a row keeps them all
// in place until the last moment ends, so no card moves under the finger. A swiped one is already off the screen.
// onCompleted gets the actions that just left the list, whichever way, so they can be taken back.
// openedAction is the action whose sheet is up, and sheetReveal how much of it shows. ownerChoices gives the first
// names of a meeting. view is kept by the caller, so it survives a change of tab.
@Composable
fun ActionsScreen(
    actions: List<TrackedAction>,
    view: ActionsView,
    onViewChange: (ActionsView) -> Unit,
    openedAction: ActionId?,
    sheetReveal: SheetReveal,
    ownerChoices: (meetingId: String) -> List<String>,
    onSetDone: (ActionId, Boolean) -> Unit,
    onSetOwner: (ActionId, String?) -> Unit,
    onSetDue: (ActionId, LocalDate?) -> Unit,
    onCompleted: (List<ActionId>) -> Unit,
    onOpenAction: (ActionId) -> Unit,
    onCloseAction: () -> Unit,
    onOpenMeeting: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = NotitiaTheme.motion
    val today = remember { today() }
    var lingering by remember { mutableStateOf(emptySet<ActionId>()) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable(stateSaver = ExpandedSaver) { mutableStateOf(emptySet<String>()) }
    var nudging by rememberSaveable(stateSaver = NudgeTargetSaver) { mutableStateOf<NudgeTarget?>(null) }
    val timeZone = remember { TimeZone.currentSystemDefault() }
    // Written after each composition, read at the next one: not a state, so keeping it does not recompose.
    val personOrder = remember(view) { mutableListOf<String?>() }
    val people = if (view == ActionsView.ByPerson) {
        actionsByPerson(actions, today, lingering).keptInOrder(personOrder)
    } else {
        emptyList()
    }
    SideEffect {
        personOrder.clear()
        personOrder.addAll(people.map { it.key })
    }
    val completed by rememberUpdatedState(onCompleted)
    LaunchedEffect(lingering) {
        if (lingering.isEmpty()) return@LaunchedEffect
        delay(motion.ACTION_LINGER_MS.milliseconds)
        completed(lingering.toList())
        lingering = emptySet()
    }
    fun toggle(tracked: TrackedAction) {
        val done = !tracked.action.done
        lingering = if (done) lingering + tracked.id else lingering - tracked.id
        onSetDone(tracked.id, done)
    }
    ActionList(
        board = actionBoard(actions, today, lingering),
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
            onToggleDone = { toggle(tracked) },
            onOpenMinutes = { onOpenMeeting(tracked.meeting.id) },
            onNudge = nudgeFor(tracked, timeZone)?.let { { nudging = NudgeTarget.Action(tracked.id) } },
            onDismiss = onCloseAction,
        )
    }
    val nudge = when (val target = nudging) {
        null -> null
        is NudgeTarget.Action -> actions.firstOrNull { it.id == target.id }?.let { nudgeFor(it, timeZone) }
        is NudgeTarget.Person -> actionsByPerson(actions, today).firstOrNull { it.key == target.key }?.let { nudgeFor(it, timeZone) }
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
            is NudgeTarget.Action -> arrayListOf(target.id.meetingId, target.id.index.toString())
            is NudgeTarget.Person -> arrayListOf(target.key)
        }
    },
    restore = { saved ->
        if (saved.size == 2) NudgeTarget.Action(ActionId(saved[0], saved[1].toInt())) else NudgeTarget.Person(saved[0])
    },
)

internal fun today(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

// An Android Bundle keeps a list of strings, not a set.
private val ExpandedSaver = Saver<Set<String>, ArrayList<String>>(save = { ArrayList(it) }, restore = { it.toSet() })

private fun PersonActions.expandKey(): String = key ?: NOBODY

@Composable
private fun ActionList(
    board: ActionBoard,
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
    val empty = board.toComplete.isEmpty() && board.groups.isEmpty() && board.done.isEmpty()

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
                    OpenCount(board)
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
                    hint = stringResource(Res.string.actions_empty_hint),
                    modifier = Modifier.padding(top = spacing.xxxl),
                )
            }
        }
        if (view == ActionsView.ByPerson) {
            itemsIndexed(people, key = { _, person -> "person/${person.expandKey()}" }) { index, person ->
                PersonCard(
                    person = person,
                    today = today,
                    expanded = person.expandKey() in expanded,
                    onExpandedChange = { onExpandedChange(person.expandKey(), it) },
                    onToggle = onToggle,
                    onOpen = onOpen,
                    onNudge = personNudgeOf(person),
                    modifier = listMotion().padding(top = if (index == 0) spacing.l else spacing.s),
                )
            }
            return@LazyColumn
        }
        if (board.toComplete.isNotEmpty()) {
            item(key = "to-complete") {
                ToCompleteCard(board.toComplete, onOpen, listMotion().padding(top = spacing.l))
            }
        }
        board.groups.forEach { (group, inGroup) ->
            item(key = group.name) {
                GroupTitle(
                    text = stringResource(group.title),
                    modifier = listMotion().padding(top = spacing.xl, bottom = spacing.s),
                    color = if (group == DueGroup.Late) colors.red else colors.textTertiary,
                )
            }
            items(inGroup, key = { it.id.key }) { Card(it, listMotion()) }
        }
        if (board.done.isNotEmpty()) {
            item(key = "done") {
                DoneHeader(
                    count = board.done.size,
                    expanded = showDone,
                    onExpandedChange = onShowDoneChange,
                    modifier = listMotion().padding(top = spacing.xl, bottom = spacing.s),
                )
            }
            // One block that unfolds and folds back as a whole, so the list follows its height instead of jumping.
            // Its cards are composed apart from the groups: one joins it fresh, never with the swipe it left with.
            item(key = "done-list") {
                AnimatedVisibility(
                    visible = showDone,
                    modifier = listMotion(),
                    enter = foldEnter(reducedMotion()),
                    exit = foldExit(reducedMotion()),
                ) {
                    Column {
                        board.done.forEach { tracked -> key(tracked.id.key) { Card(tracked, Modifier) } }
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

// Cards fade in and out and the others slide into place; with reduced motion, only the fades.
@Composable
private fun LazyItemScope.listMotion(): Modifier {
    val motion = NotitiaTheme.motion
    return Modifier.animateItem(
        fadeInSpec = motion.quick(),
        placementSpec = if (reducedMotion()) null else motion.standard(),
        fadeOutSpec = motion.instant(),
    )
}

// "7 en cours · 2 en retard": what is left, and what is late in red.
@Composable
private fun OpenCount(board: ActionBoard) {
    if (board.open == 0) {
        DetailsLine(listOf(stringResource(Res.string.actions_nothing_open)))
    } else {
        DetailsLine(
            parts = listOf(stringResource(Res.string.actions_open, board.open)),
            alert = board.late.takeIf { it > 0 }?.let { stringResource(Res.string.actions_late, it) },
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
    actions: List<TrackedAction>,
    showDone: Boolean,
    view: ActionsView = ActionsView.ByDue,
    expanded: Set<String> = emptySet(),
) = NotitiaTheme {
    val today = today()
    ActionList(
        board = actionBoard(actions, today),
        people = actionsByPerson(actions, today),
        view = view,
        onViewChange = {},
        today = today,
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

private fun sampleActions(): List<TrackedAction> {
    val now = Clock.System.now()
    return trackedActions(sampleMinutes(now))
}

@NotitiaPreviews
@Composable
private fun ActionsByDuePreview() = ActionListPreview(sampleActions(), showDone = false)

@NotitiaPreviews
@Composable
private fun ActionsWithDonePreview() = ActionListPreview(sampleActions(), showDone = true)

@NotitiaPreviews
@Composable
private fun ActionsByPersonPreview() {
    val actions = sampleActions()
    val first = actionsByPerson(actions, today()).firstOrNull()?.expandKey()
    ActionListPreview(actions, showDone = false, view = ActionsView.ByPerson, expanded = setOfNotNull(first))
}

@NotitiaPreviews
@Composable
private fun ActionsAllDonePreview() =
    ActionListPreview(sampleActions().map { it.copy(action = it.action.copy(done = true)) }, showDone = false)

@NotitiaPreviews
@Composable
private fun ActionsEmptyPreview() = ActionListPreview(emptyList(), showDone = false)
