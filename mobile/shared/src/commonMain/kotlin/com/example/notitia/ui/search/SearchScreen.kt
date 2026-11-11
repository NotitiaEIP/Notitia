package com.example.notitia.ui.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.notitia.ui.components.GroupTitle
import com.example.notitia.ui.components.HeaderAction
import com.example.notitia.ui.components.ScreenHeader
import com.example.notitia.ui.components.cardShadow
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.sampleItems
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.history
import notitia.shared.generated.resources.search_answer_note
import notitia.shared.generated.resources.search_count
import notitia.shared.generated.resources.search_history
import notitia.shared.generated.resources.search_meetings
import notitia.shared.generated.resources.search_no_answer
import notitia.shared.generated.resources.search_no_answer_hint
import notitia.shared.generated.resources.search_no_word
import notitia.shared.generated.resources.search_no_word_hint
import notitia.shared.generated.resources.search_off
import notitia.shared.generated.resources.search_passages
import notitia.shared.generated.resources.search_source
import notitia.shared.generated.resources.search_sources
import notitia.shared.generated.resources.search_title
import notitia.shared.generated.resources.search_try
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// Keeps the citation number on the line of the words it backs.
private const val CITATION_GLUE = ' '

@Composable
fun SearchScreen(
    meetings: List<MeetingListItem>,
    state: SearchState,
    historyOpen: Boolean,
    onOpenHistory: () -> Unit,
    onOpenMeeting: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    val now = remember(meetings) { Clock.System.now() }

    // Closed by back or a gesture, the keyboard takes the focus with it: no outline or send button without it.
    // Derived, so the screen recomposes when the keyboard shows or hides, not on each frame of its slide.
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    LaunchedEffect(imeVisible) { if (!imeVisible) focusManager.clearFocus() }
    // Keyed on the question, not on the step, which changes between the two phases.
    val asking = (state.step as? SearchStep.Asking)?.question
    LaunchedEffect(asking) { if (asking != null) state.answer(asking) }
    LaunchedEffect(state.fieldRequested) {
        if (state.fieldRequested) {
            fieldFocus.requestFocus()
            keyboard?.show()
            state.fieldFocused()
        }
    }
    // With the history open, back closes it and nothing else, whichever handler was added last.
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.step != SearchStep.Home && !historyOpen,
        onBackCompleted = state::reset,
    )

    SearchLayout(
        step = state.step,
        input = state.input,
        meetings = meetings,
        now = now,
        fieldFocus = fieldFocus,
        revealing = state.revealing,
        onRevealed = state::revealed,
        onLaunch = { text ->
            focusManager.clearFocus()
            state.submit(text)
        },
        onClear = state::startNewSearch,
        onOpenHistory = {
            focusManager.clearFocus()
            onOpenHistory()
        },
        onDismissKeyboard = { focusManager.clearFocus() },
        onOpenMeeting = onOpenMeeting,
        // After the insets App consumed: the field sits right on the keyboard, the tab bar stays under it.
        modifier = modifier.imePadding(),
    )
}

@Composable
private fun SearchLayout(
    step: SearchStep,
    input: TextFieldState,
    meetings: List<MeetingListItem>,
    now: Instant,
    fieldFocus: FocusRequester,
    revealing: Boolean,
    onRevealed: () -> Unit,
    onLaunch: (String) -> Unit,
    onClear: () -> Unit,
    onOpenHistory: () -> Unit,
    onDismissKeyboard: () -> Unit,
    onOpenMeeting: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NotitiaTheme.spacing
    Column(modifier.fillMaxSize()) {
        ScreenHeader(stringResource(Res.string.search_title), Modifier.padding(horizontal = spacing.l)) {
            HeaderAction(
                icon = painterResource(Res.drawable.history),
                description = stringResource(Res.string.search_history),
                onClick = onOpenHistory,
            )
        }
        AnimatedContent(
            targetState = step,
            contentKey = { it.searched },
            transitionSpec = { NotitiaTheme.motion.swap() },
            modifier = Modifier
                .weight(1f)
                .pointerInput(Unit) { detectTapGestures { onDismissKeyboard() } },
            label = "search",
        ) { shown ->
            when {
                shown == SearchStep.Home -> Suggestions(onLaunch)
                shown is SearchStep.Words -> WordsFound(shown, meetings, now, onOpenMeeting)
                shown is SearchStep.NothingFound && shown.kind == QueryKind.Words -> NothingFound(
                    sentence = stringResource(Res.string.search_no_word, shown.query),
                    hint = stringResource(Res.string.search_no_word_hint),
                    modifier = Modifier.fillMaxSize().padding(spacing.l),
                )
                else -> QuestionFound(shown, meetings, now, revealing, onRevealed, onLaunch, onOpenMeeting)
            }
        }
        SearchField(
            state = input,
            followUp = step is SearchStep.Answered,
            focusRequester = fieldFocus,
            onSubmit = { onLaunch(input.text.toString()) },
            onClear = onClear,
            modifier = Modifier.padding(horizontal = spacing.l, vertical = spacing.s),
        )
    }
}

// Scrolls on its own, between the title and the field.
@Composable
private fun Results(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NotitiaTheme.spacing.l),
    ) {
        content()
    }
}

// Right above the field, where the thumb already is.
@Composable
private fun Suggestions(onLaunch: (String) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NotitiaTheme.spacing.l)
                .padding(bottom = NotitiaTheme.spacing.s),
        ) {
            GroupTitle(stringResource(Res.string.search_try))
            Chips(sampleSuggestions, onLaunch, Modifier.padding(top = NotitiaTheme.spacing.s))
        }
    }
}

@Composable
private fun WordsFound(
    step: SearchStep.Words,
    meetings: List<MeetingListItem>,
    now: Instant,
    onOpenMeeting: (String) -> Unit,
) {
    val groups = remember(step, meetings) { groupByMeeting(step.passages, meetings) }
    val passages = groups.sumOf { it.second.size }
    Results {
        Text(
            text = stringResource(
                Res.string.search_count,
                pluralStringResource(Res.plurals.search_passages, passages, passages),
                pluralStringResource(Res.plurals.search_meetings, groups.size, groups.size),
            ),
            style = NotitiaTheme.typography.caption,
            color = NotitiaTheme.colors.textSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Column(
            modifier = Modifier.padding(top = NotitiaTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
        ) {
            groups.forEach { (meeting, found) ->
                FoundMeetingCard(meeting, found, step.query, now, onClick = { onOpenMeeting(meeting.id) })
            }
        }
    }
}

// The question stays in place from one phase to the next: only what is under it changes.
@Composable
private fun QuestionFound(
    step: SearchStep,
    meetings: List<MeetingListItem>,
    now: Instant,
    revealing: Boolean,
    onRevealed: () -> Unit,
    onLaunch: (String) -> Unit,
    onOpenMeeting: (String) -> Unit,
) {
    val spacing = NotitiaTheme.spacing
    QuestionColumn(
        question = {
            Text(
                text = step.searched.orEmpty(),
                style = NotitiaTheme.typography.title2,
                modifier = Modifier
                    .padding(start = spacing.l, top = spacing.l, end = spacing.l)
                    .semantics { heading() },
            )
        },
    ) {
        AnimatedContent(
            targetState = step,
            contentKey = { it::class },
            // The loader leaves; what replaces it comes in on its own, piece by piece.
            transitionSpec = { EnterTransition.None togetherWith NotitiaTheme.motion.swap().initialContentExit },
            label = "answer",
        ) { shown ->
            if (shown is SearchStep.Asking) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SearchLoader(meetings.size, shown.found, Modifier.padding(spacing.l))
                }
                return@AnimatedContent
            }
            // Read once: an answer plays as it arrives, and is already there when the screen comes back.
            val animate = remember { revealing }
            LaunchedEffect(Unit) { if (animate) onRevealed() }
            if (shown is SearchStep.Answered) {
                AnswerFound(
                    answer = shown.answer,
                    meetings = meetings,
                    now = now,
                    animate = animate,
                    onLaunch = onLaunch,
                    onOpenMeeting = onOpenMeeting,
                    modifier = Modifier.padding(start = spacing.l, top = spacing.m, end = spacing.l, bottom = spacing.l),
                )
            } else {
                NothingFound(
                    sentence = stringResource(Res.string.search_no_answer),
                    hint = stringResource(Res.string.search_no_answer_hint),
                    modifier = Modifier.fillMaxWidth().padding(spacing.l).reveal(0, animate),
                )
            }
        }
    }
}

// The question on top, then what fills at least the rest of the screen: a loader or a message sits in its
// middle, and an answer longer than the screen scrolls along with the question.
@Composable
private fun QuestionColumn(question: @Composable () -> Unit, rest: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = constraints.maxHeight
        Layout(
            contents = listOf(question, rest),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) { (questionMeasurables, restMeasurables), constraints ->
            val top = questionMeasurables.single().measure(constraints)
            val bottom = restMeasurables.single().measure(
                constraints.copy(minHeight = (screen - top.height).coerceAtLeast(0)),
            )
            layout(constraints.maxWidth, top.height + bottom.height) {
                top.place(0, 0)
                bottom.place(0, top.height)
            }
        }
    }
}

// Shows the answer piece by piece, as it arrives. With reduced motion, the pieces only fade in.
@Composable
private fun Modifier.reveal(order: Int, animate: Boolean): Modifier {
    val motion = NotitiaTheme.motion
    val rise = if (reducedMotion()) 0f else with(LocalDensity.current) { NotitiaTheme.spacing.s.toPx() }
    // Previews run no effect: they show the answer at once.
    val play = animate && !LocalInspectionMode.current
    val progress = remember { Animatable(if (play) 0f else 1f) }
    if (play) {
        LaunchedEffect(Unit) {
            delay((order * motion.REVEAL_STAGGER_MS).milliseconds)
            progress.animateTo(1f, motion.arrive())
        }
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * rise
    }
}

@Composable
private fun AnswerFound(
    answer: Answer,
    meetings: List<MeetingListItem>,
    now: Instant,
    animate: Boolean,
    onLaunch: (String) -> Unit,
    onOpenMeeting: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NotitiaTheme.spacing
    val sources = answer.sources.size
    Column(modifier) {
        AnswerText(answer, Modifier.reveal(0, animate))
        Text(
            text = pluralStringResource(Res.plurals.search_answer_note, sources, sources),
            style = NotitiaTheme.typography.caption,
            color = NotitiaTheme.colors.textSecondary,
            modifier = Modifier
                .padding(top = spacing.s)
                .reveal(1, animate)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        GroupTitle(stringResource(Res.string.search_sources), Modifier.padding(top = spacing.xl).reveal(2, animate))
        Column(
            modifier = Modifier.padding(top = spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            answer.sources.forEachIndexed { index, passage ->
                meetings.firstOrNull { it.id == passage.meetingId }?.let { meeting ->
                    SourceCard(
                        number = index + 1,
                        passage = passage,
                        meeting = meeting,
                        now = now,
                        onClick = { onOpenMeeting(meeting.id) },
                        modifier = Modifier.reveal(3 + index, animate),
                    )
                }
            }
        }
        Chips(answer.followUps, onLaunch, Modifier.padding(top = spacing.l).reveal(3 + sources, animate))
    }
}

@Composable
private fun AnswerText(answer: Answer, modifier: Modifier = Modifier) {
    val labels = answer.sources.indices.map { stringResource(Res.string.search_source, it + 1) }
    val badge = with(LocalDensity.current) { citationSize().toSp() }
    val text = buildAnnotatedString {
        answer.parts.forEach { part ->
            append(part.text)
            part.source?.let {
                append(CITATION_GLUE)
                // Screen readers read the alternate text: "source 1".
                appendInlineContent(citationId(it), labels[it])
            }
        }
    }
    Text(
        text = text,
        style = NotitiaTheme.typography.body,
        modifier = modifier,
        inlineContent = answer.sources.indices.associate { index ->
            citationId(index) to InlineTextContent(Placeholder(badge, badge, PlaceholderVerticalAlign.TextCenter)) {
                CitationBadge(index + 1, Modifier.fillMaxSize().clearAndSetSemantics {})
            }
        },
    )
}

private fun citationId(source: Int) = "source-$source"

// No button: the field right under it is where to try again.
@Composable
private fun NothingFound(sentence: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(Res.drawable.search_off),
            contentDescription = null,
            tint = NotitiaTheme.colors.textTertiary,
            modifier = Modifier.size(NotitiaTheme.sizes.icon),
        )
        Text(
            text = sentence,
            style = NotitiaTheme.typography.body,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = NotitiaTheme.spacing.s)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            text = hint,
            style = NotitiaTheme.typography.callout,
            color = NotitiaTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = NotitiaTheme.spacing.xs),
        )
    }
}

// A touch launches the search written on the chip.
@Composable
private fun Chips(texts: List<String>, onLaunch: (String) -> Unit, modifier: Modifier = Modifier) {
    val spacing = NotitiaTheme.spacing
    val shape = NotitiaTheme.shapes.full
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        texts.forEach { text ->
            Box(
                modifier = Modifier
                    .heightIn(min = NotitiaTheme.sizes.touchTarget)
                    .cardShadow(shape)
                    .background(NotitiaTheme.colors.surface)
                    .clickable(role = Role.Button, onClick = { onLaunch(text) })
                    .padding(horizontal = spacing.l, vertical = spacing.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = text, style = NotitiaTheme.typography.callout)
            }
        }
    }
}

@Composable
private fun SearchLayoutPreview(step: SearchStep, input: String = "") = NotitiaTheme {
    val now = Clock.System.now()
    SearchLayout(
        step = step,
        input = remember { TextFieldState(input) },
        meetings = sampleItems(now),
        now = now,
        fieldFocus = remember { FocusRequester() },
        revealing = false,
        onRevealed = {},
        onLaunch = {},
        onClear = {},
        onOpenHistory = {},
        onDismissKeyboard = {},
        onOpenMeeting = {},
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}

private val previewQuestion = sampleSuggestions[1]

@NotitiaPreviews
@Composable
private fun SearchHomePreview() = SearchLayoutPreview(SearchStep.Home)

@NotitiaPreviews
@Composable
private fun SearchWordsPreview() =
    SearchLayoutPreview(SearchStep.Words("budget", findPassages("budget", samplePassages)), input = "budget")

@NotitiaPreviews
@Composable
private fun SearchLookingPreview() = SearchLayoutPreview(SearchStep.Asking(previewQuestion, found = null))

@NotitiaPreviews
@Composable
private fun SearchWritingPreview() = SearchLayoutPreview(SearchStep.Asking(previewQuestion, found = 2))

@NotitiaPreviews
@Composable
private fun SearchAnsweredPreview() =
    SearchLayoutPreview(SearchStep.Answered(previewQuestion, checkNotNull(sampleAnswer(previewQuestion))))

@NotitiaPreviews
@Composable
private fun SearchNoWordPreview() =
    SearchLayoutPreview(SearchStep.NothingFound("budgte", QueryKind.Words), input = "budgte")

@NotitiaPreviews
@Composable
private fun SearchNoAnswerPreview() =
    SearchLayoutPreview(SearchStep.NothingFound("Quand a lieu la soutenance ?", QueryKind.Question))
