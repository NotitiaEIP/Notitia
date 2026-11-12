package com.example.notitia

import android.content.Context
import com.example.notitia.ai.SpeechEngine

// Without the gateway in local.properties, a debug build records like a release build: a made-up recording.
fun speechEngineForBuild(context: Context): SpeechEngine? {
    if (BuildConfig.GATEWAY_URL.isBlank() || BuildConfig.GATEWAY_KEY.isBlank()) return null
    return GatewaySpeechEngine(context.applicationContext, BuildConfig.GATEWAY_URL, BuildConfig.GATEWAY_KEY)
}
