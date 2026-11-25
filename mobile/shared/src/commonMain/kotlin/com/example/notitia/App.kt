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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.RecorderState
import com.example.notitia.reminder.ReminderTap
import com.example.notitia.ui.actions.ActionsScreen
import com.example.notitia.ui.actions.ActionsView
import com.example.notitia.ui.actions.RecedesUnderSheet
import com.example.notitia.ui.actions.SheetReveal
import com.example.notitia.ui.actions.ownerChoices
import com.example.notitia.ui.actions.trackedActions
import com.example.notitia.ui.components.CenteredContent
import com.example.notitia.ui.components.NotitiaTabBar
import com.example.notitia.ui.components.offerUndo
import com.example.notitia.ui.components.rememberToday
import com.example.notitia.ui.components.rememberMicrophoneAccess
import com.example.notitia.ui.meetings.MeetingSelection
import com.example.notitia.ui.meetings.MeetingsScreen
import com.example.notitia.ui.meetings.MeetingsViewModel
import com.example.notitia.ui.meetings.SelectionToolbar
import com.example.notitia.ui.meetings.listItem
import com.example.notitia.ui.meetings.status
import com.example.notitia.ui.minutes.MinutesRoute
import com.example.notitia.ui.minutes.toMinutes
import com.example.notitia.ui.navigation.Tab
import com.example.notitia.ui.recording.PlayerPosition
import com.example.notitia.ui.recording.RecordingFailedAlert
import com.example.notitia.ui.recording.RecordingPlayerHost
import com.example.notitia.ui.recording.RecordingPlayerState
import com.example.notitia.ui.search.SearchHistoryDrawer
import com.example.notitia.ui.search.SearchScreen
import com.example.notitia.ui.search.SearchState
import com.example.notitia.ui.search.move
import com.example.notitia.ui.settings.SettingsRoute
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.SelectionStyle
import com.example.notitia.ui.theme.SystemBarsFollow
import com.example.notitia.ui.theme.ThemeChoice
import com.example.notitia.ui.theme.isDark
import com.example.notitia.ui.theme.personColors
import com.example.notitia.ui.theme.reducedMotion
import com.example.notitia.ui.theme.selectionStyle
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
    notitia: Notitia = remember { Notitia() },
    recordRequests: Int = 0,
    reminderTap: ReminderTap? = null,
    // Android holds its splash screen until then, so the list never opens empty for a frame.
    onLoaded: () -> Unit = {},
) {
    // Not saved when the app closes yet: it follows the phone again on the next launch.
    var theme by rememberSaveable { mutableStateOf(ThemeChoice.System) }
    val darkTheme = theme.isDark()
    SystemBarsFollow(darkTheme)
    val meetingsModel = viewModel {
        MeetingsViewModel(notitia.meetings, notitia.recording, notitia.clock)
    }
    val kept = meetingsModel.meetings.collectAsStateWithLifecycle()
    val recorder = meetingsModel.recording.collectAsStateWithLifecycle()
    val abilities by meetingsModel.abilities.collectAsStateWithLifecycle()
    val pace by meetingsModel.pace.collectAsStateWithLifecycle()
    // The microphone moves the recorder ten times per second: the screens follow only a recording that starts, stops
    // or fails, and the sheet reads the rest.
    val recordingStart by remember { derivedStateOf { (recorder.value as? RecorderState.Active)?.startedAt } }
    val recordingId by remember { derivedStateOf { (recorder.value as? RecorderState.Active)?.meetingId } }
    val failure by remember { derivedStateOf { (recorder.value as? RecorderState.Failed)?.problem } }
    // The meeting being recorded is the mini bar's from its first sound: it joins the list once stopped, and its
    // changes while it records leave the list as it is.
    val listed by remember { derivedStateOf { kept.value?.filterNot { it.id == recordingId } } }
    val people = remember(listed) { personColors(listed.orEmpty()) }
    NotitiaTheme(darkTheme = darkTheme, people = people) {
        var tab by rememberSaveable { mutableStateOf(Tab.Meetings) }
        val stored = listed ?: return@NotitiaTheme
        LaunchedEffect(Unit) { onLoaded() }
        val today = rememberToday()
        val meetings = stored.map { it.listItem(it.status(today, abilities, pace.remaining(it))) }
        var actionsView by rememberSaveable { mutableStateOf(ActionsView.ByDue) }
        val snackbars = remember { SnackbarHostState() }
        var openedMeetingId by rememberSaveable { mutableStateOf<String?>(null) }
        // ActionId.key: a saved state takes a string, not the class.
        var openedActionKey by rememberSaveable { mutableStateOf<String?>(null) }
        val actionSheet = remember { SheetReveal() }
        val openedMeeting = stored.zip(meetings).firstOrNull { (meeting, _) -> meeting.id == openedMeetingId }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        val selection = rememberSaveable(saver = MeetingSelection.saver()) { MeetingSelection() }
        // A reminder opens its action's sheet, or the list by due date when several actions share its day.
        LaunchedEffect(reminderTap) {
            val keys = reminderTap?.actionKeys ?: return@LaunchedEffect
            tab = Tab.Actions
            actionsView = ActionsView.ByDue
            openedMeetingId = null
            settingsOpen = false
            selection.end()
            openedActionKey = keys.singleOrNull()
        }
        val search = rememberSaveable(saver = SearchState.saver(notitia.answerEngine)) { SearchState(notitia.answerEngine) }
        val historyDrawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val reduced = reducedMotion()
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { search.leave() }
        LifecycleEventEffect(Lifecycle.Event.ON_START) {
            if (search.comeBack()) scope.launch { historyDrawer.snapTo(DrawerValue.Closed) }
        }
        val microphone = rememberMicrophoneAccess()
        val haptics = LocalHapticFeedback.current
        // Asked on the first recording, not at launch. A refusal still starts: the recorder then says why it cannot.
        fun startRecording() = microphone.request {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            meetingsModel.startRecording()
        }
        // Each touch on the home screen widget starts a recording, unless one already runs.
        LaunchedEffect(recordRequests) {
            if (recordRequests > 0 && recordingStart == null) startRecording()
        }
        // A new recording gets a new sheet, which rises from the mini bar to the full screen. Known by its start: its
        // meeting comes only with the first sound.
        val player = rememberSaveable(recordingStart, saver = RecordingPlayerState.Saver) {
            RecordingPlayerState(PlayerPosition.Collapsed, openOnStart = true)
        }

        // Over everything, tab bar included, as a modal: nothing behind the veil can be touched, or read.
        // The gesture only exists on the search screen, and not over a recording shown full screen.
        val onSearch = tab == Tab.Search && openedMeeting == null && !(recordingStart != null && player.isExpanded)
        ModalNavigationDrawer(
            drawerState = historyDrawer,
            gesturesEnabled = historyDrawer.isOpen || onSearch,
            scrimColor = NotitiaTheme.colors.dim,
            drawerContent = { SearchHistoryDrawer(historyDrawer, search) },
        ) {
            // Bounded inside the veils, so the drawer and the sheet still cover the whole window.
            RecedesUnderSheet(
                reveal = actionSheet,
                modifier = if (historyDrawer.isOpen) Modifier.clearAndSetSemantics {} else Modifier,
            ) {
                CenteredContent {
                    RecordingPlayerHost(
                        recording = { recorder.value as? RecorderState.Active },
                        state = player,
                        onStop = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            meetingsModel.stopRecording()
                        },
                        onTogglePause = {
                            val pausing = recorder.value is RecorderState.Recording
                            haptics.performHapticFeedback(if (pausing) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                            meetingsModel.togglePause()
                        },
                        tabBar = {
                            // The iPhone's selection toolbar takes the tab bar's place, as in Photos and Voice Memos.
                            val chosen = selection.chosen
                            if (chosen != null && selectionStyle == SelectionStyle.Toolbar) {
                                SelectionToolbar(count = chosen.size, onDelete = selection::askToDelete)
                            } else {
                                NotitiaTabBar(
                                    selected = tab,
                                    onSelect = {
                                        tab = it
                                        openedMeetingId = null
                                        openedActionKey = null
                                        settingsOpen = false
                                        selection.end()
                                    },
                                )
                            }
                        },
                    ) { padding ->
                        val screenModifier = Modifier.padding(padding).consumeWindowInsets(padding)
                        if (openedMeeting != null) {
                            MinutesRoute(
                                meeting = openedMeeting.first,
                                item = openedMeeting.second,
                                abilities = abilities,
                                transcript = { meetingsModel.transcript(openedMeeting.first.id) },
                                onSetDone = meetingsModel::setDone,
                                onRewrite = { meetingsModel.rewrite(openedMeeting.first.id) },
                                onRename = { meetingsModel.rename(openedMeeting.first.id, it) },
                                onDelete = {
                                    val id = openedMeeting.first.id
                                    openedMeetingId = null
                                    scope.launch { deleteOnceShown { meetingsModel.delete(setOf(id)) } }
                                },
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
                                    recording = recordingStart != null,
                                    selection = selection,
                                    onOpen = { openedMeetingId = it.id },
                                    onRecord = ::startRecording,
                                    onSettings = { settingsOpen = true },
                                    onDelete = meetingsModel::delete,
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
                                        onSetReminder = meetingsModel::setReminder,
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
        failure?.let { problem ->
            RecordingFailedAlert(
                problem = problem,
                onOpenSettings = microphone::openSettings,
                onDismiss = meetingsModel::dismissFailure,
            )
        }
    }
}

// The list must be on screen before the meeting leaves it, or its card is already gone when it appears. Two frames:
// one to show the list, one for the card to have been laid out.
private suspend fun deleteOnceShown(delete: () -> Unit) {
    repeat(2) { withFrameNanos {} }
    delete()
}
