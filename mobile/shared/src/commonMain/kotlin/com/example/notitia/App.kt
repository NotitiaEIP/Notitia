package com.example.notitia

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
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
import com.example.notitia.ui.navigation.Tab
import com.example.notitia.ui.search.SearchScreen
import com.example.notitia.ui.theme.NotitiaTheme
import kotlin.time.Clock

// No transition between screens: the content switches at once, only the tab bar animates.
@Composable
@Preview
fun App() {
    NotitiaTheme {
        var tab by rememberSaveable { mutableStateOf(Tab.Meetings) }
        // Made-up meetings until US-16 stores real ones.
        val meetings = remember { sampleMeetings(Clock.System.now()) }
        Scaffold(
            bottomBar = { NotitiaTabBar(selected = tab, onSelect = { tab = it }) },
        ) { padding ->
            val screenModifier = Modifier.padding(padding).consumeWindowInsets(padding)
            when (tab) {
                Tab.Meetings -> MeetingsScreen(
                    meetings = meetings,
                    onOpen = {},
                    onRecord = {},
                    onSettings = {},
                    modifier = screenModifier,
                )
                Tab.Actions -> ActionsScreen(screenModifier)
                Tab.Search -> SearchScreen(screenModifier)
            }
        }
    }
}
