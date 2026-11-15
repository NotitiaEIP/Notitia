package com.example.notitia

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.example.notitia.bridge.CoreSelfCheck
import com.example.notitia.bridge.NotitiaCore
import com.example.notitia.reminder.NotificationReminderScheduler
import com.example.notitia.reminder.reminderTaps
import com.example.notitia.storage.phoneMeetingStore

private var notitia: Notitia? = null

// Once per process, like the Android Application: SwiftUI may build the view controller again. Only the main
// thread comes here.
private fun notitia(core: NotitiaCore): Notitia = notitia ?: Notitia(
    core = core,
    meetingStore = phoneMeetingStore(),
    reminderScheduler = NotificationReminderScheduler(),
).also { notitia = it }

// The core comes from Swift: only Swift can call the Rust library on the iPhone. Compose would slide the whole
// screen up to show a focused field. The search field places itself on the keyboard instead, with imePadding.
fun MainViewController(core: NotitiaCore) = ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) {
    val reminderTap by reminderTaps.collectAsState()
    App(
        notitia = notitia(core),
        reminderTap = reminderTap,
    )
}

// For Swift, which sees neither the default arguments of CoreSelfCheck nor their types.
fun coreSelfCheckMarker(core: NotitiaCore): String = CoreSelfCheck(core).run()
