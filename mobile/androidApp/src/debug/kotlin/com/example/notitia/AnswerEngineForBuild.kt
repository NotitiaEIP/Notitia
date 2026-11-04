package com.example.notitia

import com.example.notitia.ui.search.AnswerEngine
import com.example.notitia.ui.search.RagAnswerEngine
import com.example.notitia.ui.search.SampleAnswerEngine

// Without the gateway in local.properties, a debug build answers like a release build.
fun answerEngineForBuild(): AnswerEngine =
    if (BuildConfig.GATEWAY_URL.isBlank() || BuildConfig.GATEWAY_KEY.isBlank()) {
        SampleAnswerEngine
    } else {
        RagAnswerEngine(GatewayLanguageModel(BuildConfig.GATEWAY_URL, BuildConfig.GATEWAY_KEY))
    }
