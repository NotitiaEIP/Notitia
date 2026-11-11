package com.example.notitia

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.example.notitia.storage.phoneMeetingStore
import com.example.notitia.widget.ACTION_RECORD

class MainActivity : ComponentActivity() {
    // Counts the touches on the widget, also those that come while the app is already open.
    private var recordRequests by mutableIntStateOf(0)
    private var meetingsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After a rotation the intent is the same one, already handled.
        if (savedInstanceState == null) countRecordRequest(intent)

        val answerEngine = answerEngineForBuild()
        val speechEngine = speechEngineForBuild(this, savedInstanceState)
        val minutesWriter = minutesWriterForBuild()
        val meetingStore = phoneMeetingStore(this)
        holdSplashScreenUntil { meetingsLoaded }
        setContent {
            App(
                answerEngine = answerEngine,
                speechEngine = speechEngine,
                minutesWriter = minutesWriter,
                meetingStore = meetingStore,
                recordRequests = recordRequests,
                onLoaded = { meetingsLoaded = true },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        countRecordRequest(intent)
    }

    // Android 12+ keeps its splash screen while no frame is drawn; older versions keep the window background.
    private fun holdSplashScreenUntil(ready: () -> Boolean) {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean = ready().also { if (it) content.viewTreeObserver.removeOnPreDrawListener(this) }
        })
    }

    // Reopening the app from the recent apps replays the intent that started it: that is not a new touch.
    private fun countRecordRequest(intent: Intent) {
        val fromRecents = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (intent.action == ACTION_RECORD && !fromRecents) recordRequests++
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
