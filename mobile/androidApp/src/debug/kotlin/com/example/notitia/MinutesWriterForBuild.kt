package com.example.notitia

import com.example.notitia.ui.minutes.LanguageModelMinutesWriter
import com.example.notitia.ui.minutes.MinutesWriter

// Without the gateway in local.properties, a debug build has no minutes writer, like a release build.
fun minutesWriterForBuild(): MinutesWriter? =
    if (BuildConfig.GATEWAY_URL.isBlank() || BuildConfig.GATEWAY_KEY.isBlank()) {
        null
    } else {
        LanguageModelMinutesWriter(GatewayLanguageModel(BuildConfig.GATEWAY_URL, BuildConfig.GATEWAY_KEY))
    }
