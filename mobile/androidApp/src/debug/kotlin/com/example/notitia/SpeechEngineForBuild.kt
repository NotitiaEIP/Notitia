package com.example.notitia

import com.example.notitia.ai.SpeechEngine

// Without the gateway in local.properties, a debug build records like a release build: the meeting keeps no text.
fun speechEngineForBuild(): SpeechEngine? {
    if (BuildConfig.GATEWAY_URL.isBlank() || BuildConfig.GATEWAY_KEY.isBlank()) return null
    return GatewaySpeechEngine(BuildConfig.GATEWAY_URL, BuildConfig.GATEWAY_KEY)
}
