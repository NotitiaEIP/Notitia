package com.example.notitia

import com.example.notitia.ai.Engine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

// Without the gateway in local.properties, a debug build lends no engine, like a release build: the meetings wait for
// a model.
fun enginesForBuild(): Flow<List<Engine>> =
    if (BuildConfig.GATEWAY_URL.isBlank() || BuildConfig.GATEWAY_KEY.isBlank()) {
        emptyFlow()
    } else {
        flowOf(gatewayEngines(Gateway(BuildConfig.GATEWAY_URL, BuildConfig.GATEWAY_KEY)))
    }
