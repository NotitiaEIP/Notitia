package com.example.notitia

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.example.notitia.reminder.NotificationReminderScheduler
import com.example.notitia.reminder.reminderTaps
import com.example.notitia.storage.phoneMeetingStore

// Compose would slide the whole screen up to show a focused field. The search field places itself on the
// keyboard instead, with imePadding.
fun MainViewController() = ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) {
    val reminderTap by reminderTaps.collectAsState()
    App(
        meetingStore = remember { phoneMeetingStore() },
        reminderScheduler = remember { NotificationReminderScheduler() },
        reminderTap = reminderTap,
    )
}