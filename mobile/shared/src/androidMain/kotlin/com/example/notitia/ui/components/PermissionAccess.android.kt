package com.example.notitia.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

// Before Android 13 there is no question to ask: notifications are on unless turned off in the settings.
@Composable
actual fun rememberNotificationAccess(): PermissionAccess = rememberPermissionAccess(
    permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null,
    allowed = { NotificationManagerCompat.from(it).areNotificationsEnabled() },
    settings = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName)
        } else {
            appDetails(it)
        }
    },
)

@Composable
actual fun rememberMicrophoneAccess(): PermissionAccess = rememberPermissionAccess(
    permission = Manifest.permission.RECORD_AUDIO,
    allowed = { it.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
    settings = ::appDetails,
)

private fun appDetails(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

@Composable
private fun rememberPermissionAccess(
    permission: String?,
    allowed: (Context) -> Boolean,
    settings: (Context) -> Intent,
): PermissionAccess {
    val context = LocalContext.current
    val access = remember(context) { AndroidPermissionAccess(context, permission, allowed, settings) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access.answered() }
    SideEffect { access.launcher = launcher }
    LifecycleResumeEffect(access) {
        access.refresh()
        onPauseOrDispose {}
    }
    return access
}

private class AndroidPermissionAccess(
    private val context: Context,
    private val permission: String?,
    private val allowed: (Context) -> Boolean,
    private val settings: (Context) -> Intent,
) : PermissionAccess {
    override var granted by mutableStateOf(allowed(context))
        private set
    var launcher: ManagedActivityResultLauncher<String, Boolean>? = null
    private var onResult: ((Boolean) -> Unit)? = null

    fun refresh() {
        granted = allowed(context)
    }

    fun answered() {
        refresh()
        onResult?.invoke(granted)
        onResult = null
    }

    override fun request(onResult: (granted: Boolean) -> Unit) {
        refresh()
        val launcher = launcher
        if (granted || permission == null || launcher == null) {
            onResult(granted)
        } else {
            this.onResult = onResult
            launcher.launch(permission)
        }
    }

    override fun openSettings() = context.startActivity(settings(context))
}
