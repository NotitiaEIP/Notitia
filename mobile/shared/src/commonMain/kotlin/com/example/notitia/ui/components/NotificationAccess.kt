package com.example.notitia.ui.components

import androidx.compose.runtime.Composable

// Whether the phone lets Notitia notify. Asked when a reminder is first turned on, never at launch, and read again
// when the app comes back from the system's settings.
interface NotificationAccess {
    val granted: Boolean

    // Once access has been refused for good, the system answers at once without asking again.
    fun request(onResult: (granted: Boolean) -> Unit)

    // The app's own notification settings, where a refusal can be undone.
    fun openSettings()
}

@Composable
expect fun rememberNotificationAccess(): NotificationAccess
