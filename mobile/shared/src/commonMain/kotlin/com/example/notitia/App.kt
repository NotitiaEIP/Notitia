package com.example.notitia

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.notitia.ai.SpeechEngine
import com.example.notitia.meeting.Meeting
import com.example.notitia.reminder.NoReminderScheduler
import com.example.notitia.reminder.ReminderScheduler
import com.example.notitia.reminder.ReminderTap
import com.example.notitia.storage.MeetingRepository
import com.example.notitia.storage.MeetingStore
import com.example.notitia.storage.NoMeetingStore
import com.example.notitia.ui.actions.ActionsScreen
import com.example.notitia.ui.actions.ActionsView
import com.example.notitia.ui.actions.RecedesUnderSheet
import com.example.notitia.ui.actions.SheetReveal
import com.example.notitia.ui.actions.ownerChoices
import com.example.notitia.ui.actions.trackedActions
import com.example.notitia.ui.components.NotitiaTabBar
import com.example.notitia.ui.components.offerUndo
import com.example.notitia.ui.meetings.MeetingsScreen
import com.example.notitia.ui.meetings.MeetingsViewModel
import com.example.notitia.ui.meetings.listItem
import com.example.notitia.ui.meetings.sampleMeetings
import com.example.notitia.ui.minutes.MinutesRoute
import com.example.notitia.ui.minutes.MinutesWriter
import com.example.notitia.ui.minutes.toMinutes
import com.example.notitia.ui.navigation.Tab
import com.example.notitia.ui.recording.FakeRecording
import com.example.notitia.ui.recording.PlayerPosition
import com.example.notitia.ui.recording.RecordingPlayerHost
import com.example.notitia.ui.recording.RecordingPlayerState
import com.example.notitia.ui.search.AnswerEngine
import com.example.notitia.ui.search.SampleAnswerEngine
import com.example.notitia.ui.search.SearchHistoryDrawer
import com.example.notitia.ui.search.SearchScreen
import com.example.notitia.ui.search.SearchState
import com.example.notitia.ui.search.move
import com.example.notitia.ui.settings.SettingsRoute
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.SystemBarsFollow
import com.example.notitia.ui.theme.ThemeChoice
import com.example.notitia.ui.theme.isDark
import com.example.notitia.ui.theme.reducedMotion
import kotlin.time.Clock
import kotlinx.coroutines.launch
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.actions_done_message
import notitia.shared.generated.resources.undo
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

// No transition between screens: the content switches at once, only the tab bar animates.
@Composable
@Preview
fun App(
    answerEngine: AnswerEngine = SampleAnswerEngine,
    speechEngine: SpeechEngine? = null,
    minutesWriter: MinutesWriter? = null,
    meetingStore: MeetingStore = NoMeetingStore,
    reminderScheduler: ReminderScheduler = NoReminderScheduler,
    recordRequests: Int = 0,
    reminderTap: ReminderTap? = null,
    // Android holds its splash screen until then, so the list never opens empty for a frame.
    onLoaded: () -> Unit = {},
) {
    // Not saved when the app closes yet: it follows the phone again on the next launch.
    var theme by rememberSaveable { mutableStateOf(ThemeChoice.System) }
    val darkTheme = theme.isDark()
    SystemBarsFollow(darkTheme)
    NotitiaTheme(darkTheme = darkTheme) {
        var tab by rememberSaveable { mutableStateOf(Tab.Meetings) }
        val meetingsModel = viewModel {
            // Made-up meetings beside the user's, so the app has something to show before a first recording.
            val samples = sampleMeetings(Clock.System.now())
            MeetingsViewModel(MeetingRepository(meetingStore, samples), speechEngine, minutesWriter, reminderScheduler)
        }
        val stored = meetingsModel.meetings.collectAsStateWithLifecycle().value ?: return@NotitiaTheme
        LaunchedEffect(Unit) { onLoaded() }
        val meetings = stored.map { it.listItem(meetingsModel.progress(it.id)) }
        var actionsView by rememberSaveable { mutableStateOf(ActionsView.ByDue) }
        val snackbars = remember { SnackbarHostState() }
        var openedMeetingId by rememberSaveable { mutableStateOf<String?>(null) }
        // ActionId.key: a saved state takes a string, not the class.
        var openedActionKey by rememberSaveable { mutableStateOf<String?>(null) }
        val actionSheet = remember { SheetReveal() }
        val openedMeeting = stored.zip(meetings).firstOrNull { (meeting, _) -> meeting.id == openedMeetingId }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        // A reminder opens its action's sheet, or the list by due date when several actions share its day.
        LaunchedEffect(reminderTap) {
            val keys = reminderTap?.actionKeys ?: return@LaunchedEffect
            tab = Tab.Actions
            actionsView = ActionsView.ByDue
            openedMeetingId = null
            settingsOpen = false
            openedActionKey = keys.singleOrNull()
        }
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
        LaunchedEffect(recording?.paused) { meetingsModel.setPaused(recording?.paused == true) }
        fun startRecording() {
            val started = FakeRecording(startedAt = Clock.System.now())
            recording = started
            meetingsModel.start(started.startedAt)
        }
        // Each touch on the home screen widget starts a recording, unless one already runs.
        LaunchedEffect(recordRequests) {
            if (recordRequests > 0 && recording == null) startRecording()
        }
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
            RecedesUnderSheet(
                reveal = actionSheet,
                modifier = if (historyDrawer.isOpen) Modifier.clearAndSetSemantics {} else Modifier,
            ) {
                RecordingPlayerHost(
                    recording = recording,
                    state = player,
                    onStop = {
                        recording?.let { meetingsModel.stop(it.elapsed) }
                        recording = null
                    },
                    tabBar = {
                        NotitiaTabBar(
                            selected = tab,
                            onSelect = {
                                tab = it
                                openedMeetingId = null
                                openedActionKey = null
                                settingsOpen = false
                            },
                        )
                    },
                ) { padding ->
                    val screenModifier = Modifier.padding(padding).consumeWindowInsets(padding)
                    if (openedMeeting != null) {
                        MinutesRoute(
                            meeting = openedMeeting.first,
                            item = openedMeeting.second,
                            onSetDone = meetingsModel::setDone,
                            backLabel = stringResource(tab.title),
                            backDescription = stringResource(tab.backDescription),
                            onBack = { openedMeetingId = null },
                            modifier = screenModifier,
                        )
                    } else if (settingsOpen) {
                        SettingsRoute(
                            theme = theme,
                            onThemeChange = { theme = it },
                            onBack = { settingsOpen = false },
                            modifier = screenModifier,
                        )
                    } else {
                        when (tab) {
                            Tab.Meetings -> MeetingsScreen(
                                meetings = meetings,
                                recording = recording != null,
                                onOpen = { openedMeetingId = it.id },
                                onRecord = ::startRecording,
                                onSettings = { settingsOpen = true },
                                modifier = screenModifier,
                            )
                            Tab.Actions -> {
                                val minutes = stored.zip(meetings, Meeting::toMinutes)
                                val tracked = trackedActions(minutes)
                                ActionsScreen(
                                    actions = tracked,
                                    view = actionsView,
                                    onViewChange = { actionsView = it },
                                    openedAction = tracked.firstOrNull { it.id.key == openedActionKey }?.id,
                                    sheetReveal = actionSheet,
                                    ownerChoices = { meetingId ->
                                        minutes.firstOrNull { it.meeting.id == meetingId }
                                            ?.let(::ownerChoices)
                                            .orEmpty()
                                    },
                                    onSetDone = meetingsModel::setDone,
                                    onSetOwner = meetingsModel::setOwner,
                                    onSetDue = meetingsModel::setDue,
                                    onCompleted = { ids ->
                                        scope.launch {
                                            val message = getPluralString(Res.plurals.actions_done_message, ids.size, ids.size)
                                            if (snackbars.offerUndo(message, getString(Res.string.undo))) {
                                                ids.forEach { meetingsModel.setDone(it, false) }
                                            }
                                        }
                                    },
                                    onOpenAction = { openedActionKey = it.key },
                                    onCloseAction = { openedActionKey = null },
                                    onOpenMeeting = { openedMeetingId = it },
                                    modifier = screenModifier,
                                )
                            }
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
                    // Over the tab bar and the mini bar, and kept across tabs, so an action can still be taken back.
                    SnackbarHost(
                        hostState = snackbars,
                        modifier = Modifier.fillMaxSize().padding(padding).wrapContentHeight(Alignment.Bottom),
                    )
                }
            }
        }
    }
}
