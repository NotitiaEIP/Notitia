package com.example.notitia.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
actual fun rememberNotificationAccess(): NotificationAccess {
    val context = LocalContext.current
    val access = remember(context) { AndroidNotificationAccess(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access.answered() }
    SideEffect { access.launcher = launcher }
    LifecycleResumeEffect(access) {
        access.refresh()
        onPauseOrDispose {}
    }
    return access
}

// Before Android 13 there is no question to ask: notifications are on unless turned off in the settings.
private class AndroidNotificationAccess(private val context: Context) : NotificationAccess {
    override var granted by mutableStateOf(enabled())
        private set
    var launcher: ManagedActivityResultLauncher<String, Boolean>? = null
    private var onResult: ((Boolean) -> Unit)? = null

    fun refresh() {
        granted = enabled()
    }

    fun answered() {
        refresh()
        onResult?.invoke(granted)
        onResult = null
    }

    override fun request(onResult: (granted: Boolean) -> Unit) {
        refresh()
        val launcher = launcher
        if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || launcher == null) {
            onResult(granted)
        } else {
            this.onResult = onResult
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun openSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }
        context.startActivity(intent)
    }

    private fun enabled() = NotificationManagerCompat.from(context).areNotificationsEnabled()
}
