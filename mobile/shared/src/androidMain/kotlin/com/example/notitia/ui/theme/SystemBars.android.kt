package com.example.notitia.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
actual fun SystemBarsFollow(darkTheme: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    LaunchedEffect(activity, darkTheme) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
            // The same scrims as enableEdgeToEdge() without arguments.
            navigationBarStyle = SystemBarStyle.auto(
                Color.argb(0xE6, 0xFF, 0xFF, 0xFF),
                Color.argb(0x80, 0x1B, 0x1B, 0x1B),
            ) { darkTheme },
        )
    }
}
