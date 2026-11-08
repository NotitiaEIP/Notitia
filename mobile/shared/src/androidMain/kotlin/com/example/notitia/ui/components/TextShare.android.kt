package com.example.notitia.ui.components

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// No title: Android's own chooser, with Copy and the recent contacts at the top.
@Composable
actual fun rememberTextShare(): (text: String, onShared: () -> Unit) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { text, onShared ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            val chooser = Intent.createChooser(send, null)
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            onShared()
        }
    }
}
