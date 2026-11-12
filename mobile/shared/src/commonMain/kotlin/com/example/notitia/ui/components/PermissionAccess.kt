package com.example.notitia.ui.components

import androidx.compose.runtime.Composable

// Whether the phone lets Notitia do something. Asked the first time it is needed, never at launch, and read again
// when the app comes back from the system's settings.
interface PermissionAccess {
    val granted: Boolean

    // Once access has been refused for good, the system answers at once without asking again.
    fun request(onResult: (granted: Boolean) -> Unit)

    // The app's own settings, where a refusal can be undone.
    fun openSettings()
}

@Composable
expect fun rememberNotificationAccess(): PermissionAccess

@Composable
expect fun rememberMicrophoneAccess(): PermissionAccess
