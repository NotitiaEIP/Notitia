package com.example.notitia

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.example.notitia.ai.SpeechEngine

private const val MICROPHONE_REQUEST = 1

// Without the gateway in local.properties, a debug build records like a release build: a made-up recording.
fun speechEngineForBuild(activity: ComponentActivity, savedInstanceState: Bundle?): SpeechEngine? {
    if (BuildConfig.GATEWAY_URL.isBlank() || BuildConfig.GATEWAY_KEY.isBlank()) return null
    // Without the foreground service of US-11, Android silences the microphone once the screen goes off.
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    // Once per launch: a rotation does not ask again.
    val microphone = Manifest.permission.RECORD_AUDIO
    if (savedInstanceState == null && activity.checkSelfPermission(microphone) != PackageManager.PERMISSION_GRANTED) {
        activity.requestPermissions(arrayOf(microphone), MICROPHONE_REQUEST)
    }
    return GatewaySpeechEngine(activity.applicationContext, BuildConfig.GATEWAY_URL, BuildConfig.GATEWAY_KEY)
}
