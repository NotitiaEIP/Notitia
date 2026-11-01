package com.example.notitia

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.notitia.ui.actions.ActionsScreen
import com.example.notitia.ui.components.NotitiaTabBar
import com.example.notitia.ui.meetings.MeetingsScreen
import com.example.notitia.ui.meetings.sampleMeetings
import com.example.notitia.ui.minutes.MinutesRoute
import com.example.notitia.ui.navigation.Tab
import com.example.notitia.ui.recording.FakeRecording
import com.example.notitia.ui.recording.PlayerPosition
import com.example.notitia.ui.recording.RecordingPlayerHost
import com.example.notitia.ui.recording.RecordingPlayerState
import com.example.notitia.ui.search.SearchScreen
import com.example.notitia.ui.search.SearchState
import com.example.notitia.ui.theme.NotitiaTheme
import kotlin.time.Clock
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.meetings_title
import notitia.shared.generated.resources.minutes_back_description
import notitia.shared.generated.resources.search_back_description
import notitia.shared.generated.resources.search_title
import org.jetbrains.compose.resources.stringResource

// No transition between screens: the content switches at once, only the tab bar animates.
@Composable
@Preview
fun App() {
    NotitiaTheme {
        var tab by rememberSaveable { mutableStateOf(Tab.Meetings) }
        // Made-up meetings until US-16 stores real ones.
        val meetings = remember { sampleMeetings(Clock.System.now()) }
        var openedMeetingId by rememberSaveable { mutableStateOf<String?>(null) }
        val openedMeeting = meetings.firstOrNull { it.id == openedMeetingId }
        val search = rememberSaveable(saver = SearchState.Saver) { SearchState() }
        var recording by rememberSaveable(stateSaver = FakeRecording.Saver) { mutableStateOf(null) }
        recording?.let { current -> LaunchedEffect(current) { current.run() } }
        // A new recording gets a new sheet, which rises from the mini bar to the full screen.
        val player = rememberSaveable(recording?.startedAt, saver = RecordingPlayerState.Saver) {
            RecordingPlayerState(PlayerPosition.Collapsed, openOnStart = true)
        }

        RecordingPlayerHost(
            recording = recording,
            state = player,
            onStop = { recording = null },
            tabBar = {
                NotitiaTabBar(
                    selected = tab,
                    onSelect = {
                        // Touching the tab already shown starts a new search, as in most apps.
                        if (it == Tab.Search && tab == Tab.Search && openedMeetingId == null) search.reset()
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
                    modifier = screenModifier,
                )
            } else {
                when (tab) {
                    Tab.Meetings -> MeetingsScreen(
                        meetings = meetings,
                        recording = recording != null,
                        onOpen = { openedMeetingId = it.id },
                        onRecord = { recording = FakeRecording(startedAt = Clock.System.now()) },
                        onSettings = {},
                        modifier = screenModifier,
                    )
                    Tab.Actions -> ActionsScreen(screenModifier)
                    Tab.Search -> SearchScreen(
                        meetings = meetings,
                        state = search,
                        onOpenMeeting = { openedMeetingId = it },
                        modifier = screenModifier,
                    )
                }
            }
        }
    }
}
