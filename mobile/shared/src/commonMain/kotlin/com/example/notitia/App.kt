package com.example.notitia

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.notitia.ai.SpeechEngine
import com.example.notitia.ui.actions.ActionsScreen
import com.example.notitia.ui.components.NotitiaTabBar
import com.example.notitia.ui.meetings.MeetingsScreen
import com.example.notitia.ui.meetings.sampleMeetings
import com.example.notitia.ui.minutes.MinutesRoute
import com.example.notitia.ui.navigation.Tab
import com.example.notitia.ui.recording.FakeRecording
import com.example.notitia.ui.recording.PlayerPosition
import com.example.notitia.ui.recording.RecordedMeetings
import com.example.notitia.ui.recording.RecordingPlayerHost
import com.example.notitia.ui.recording.RecordingPlayerState
import com.example.notitia.ui.recording.listItem
import com.example.notitia.ui.search.AnswerEngine
import com.example.notitia.ui.search.SampleAnswerEngine
import com.example.notitia.ui.search.SearchHistoryDrawer
import com.example.notitia.ui.search.SearchScreen
import com.example.notitia.ui.search.SearchState
import com.example.notitia.ui.search.move
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion
import kotlin.time.Clock
import kotlinx.coroutines.launch
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.meetings_title
import notitia.shared.generated.resources.minutes_back_description
import notitia.shared.generated.resources.search_back_description
import notitia.shared.generated.resources.search_title
import org.jetbrains.compose.resources.stringResource

// No transition between screens: the content switches at once, only the tab bar animates.
@Composable
@Preview
fun App(answerEngine: AnswerEngine = SampleAnswerEngine, speechEngine: SpeechEngine? = null) {
    NotitiaTheme {
        var tab by rememberSaveable { mutableStateOf(Tab.Meetings) }
        val recordings = speechEngine?.let { viewModel { RecordedMeetings(it) } }
        // Made-up meetings until US-16 stores real ones.
        val samples = remember { sampleMeetings(Clock.System.now()) }
        val meetings = recordings?.meetings.orEmpty().map { it.listItem() } + samples
        var openedMeetingId by rememberSaveable { mutableStateOf<String?>(null) }
        val openedMeeting = meetings.firstOrNull { it.id == openedMeetingId }
        val search = rememberSaveable(saver = SearchState.saver(answerEngine)) { SearchState(answerEngine) }
        val historyDrawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val reduced = reducedMotion()
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { search.leave() }
        LifecycleEventEffect(Lifecycle.Event.ON_START) {
            if (search.comeBack()) scope.launch { historyDrawer.snapTo(DrawerValue.Closed) }
        }
        var recording by rememberSaveable(stateSaver = FakeRecording.Saver) { mutableStateOf(null) }
        recording?.let { current -> LaunchedEffect(current) { current.run() } }
        LaunchedEffect(recording?.paused) { recordings?.setPaused(recording?.paused == true) }
        // A new recording gets a new sheet, which rises from the mini bar to the full screen.
        val player = rememberSaveable(recording?.startedAt, saver = RecordingPlayerState.Saver) {
            RecordingPlayerState(PlayerPosition.Collapsed, openOnStart = true)
        }

        // Over everything, tab bar included, as a modal: nothing behind the veil can be touched, or read.
        // The gesture only exists on the search screen, and not over a recording shown full screen.
        val onSearch = tab == Tab.Search && openedMeeting == null && !(recording != null && player.isExpanded)
        ModalNavigationDrawer(
            drawerState = historyDrawer,
            gesturesEnabled = historyDrawer.isOpen || onSearch,
            scrimColor = NotitiaTheme.colors.dim,
            drawerContent = { SearchHistoryDrawer(historyDrawer, search) },
        ) {
            Box(if (historyDrawer.isOpen) Modifier.clearAndSetSemantics {} else Modifier) {
                RecordingPlayerHost(
                    recording = recording,
                    state = player,
                    onStop = {
                        recording?.let { recordings?.stop(it.elapsed) }
                        recording = null
                    },
                    tabBar = {
                        NotitiaTabBar(
                            selected = tab,
                            onSelect = {
                                tab = it
                                openedMeetingId = null
                            },
                        )
                    },
                ) { padding ->
                    val screenModifier = Modifier.padding(padding).consumeWindowInsets(padding)
                    if (openedMeeting != null) {
                        // A meeting opens over the tab it came from, and its back button names that tab.
                        val fromSearch = tab == Tab.Search
                        MinutesRoute(
                            meeting = openedMeeting,
                            backLabel = stringResource(if (fromSearch) Res.string.search_title else Res.string.meetings_title),
                            backDescription = stringResource(
                                if (fromSearch) Res.string.search_back_description else Res.string.minutes_back_description,
                            ),
                            onBack = { openedMeetingId = null },
                            transcript = recordings?.meetings?.firstOrNull { it.id == openedMeetingId }?.lines,
                            modifier = screenModifier,
                        )
                    } else {
                        when (tab) {
                            Tab.Meetings -> MeetingsScreen(
                                meetings = meetings,
                                recording = recording != null,
                                onOpen = { openedMeetingId = it.id },
                                onRecord = {
                                    val started = FakeRecording(startedAt = Clock.System.now())
                                    recording = started
                                    recordings?.start(started.startedAt)
                                },
                                onSettings = {},
                                modifier = screenModifier,
                            )
                            Tab.Actions -> ActionsScreen(screenModifier)
                            Tab.Search -> SearchScreen(
                                meetings = meetings,
                                state = search,
                                historyOpen = historyDrawer.isOpen,
                                onOpenHistory = { scope.launch { historyDrawer.move(open = true, reduced) } },
                                onOpenMeeting = { openedMeetingId = it },
                                modifier = screenModifier,
                            )
                        }
                    }
                }
            }
        }
    }
}
