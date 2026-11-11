package com.example.notitia.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenNotificationSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberNotificationAccess(): NotificationAccess {
    val access = remember { IosNotificationAccess() }
    LifecycleResumeEffect(access) {
        access.refresh()
        onPauseOrDispose {}
    }
    return access
}

private class IosNotificationAccess : NotificationAccess {
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    // iOS answers later: taken as given until then, so no warning flashes on a sheet as it opens.
    override var granted by mutableStateOf(true)
        private set

    fun refresh() {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            val allowed = status == UNAuthorizationStatusAuthorized ||
                status == UNAuthorizationStatusProvisional ||
                status == UNAuthorizationStatusEphemeral
            onMain { granted = allowed }
        }
    }

    override fun request(onResult: (granted: Boolean) -> Unit) {
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { allowed, _ ->
            onMain {
                granted = allowed
                onResult(allowed)
            }
        }
    }

    override fun openSettings() {
        val settings = NSURL.URLWithString(UIApplicationOpenNotificationSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(settings, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}

// UserNotifications answers on a queue of its own; Compose state is changed on the main thread.
private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)
