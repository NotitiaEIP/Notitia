package com.example.notitia

import com.example.notitia.ai.Engine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

// No engine yet in a release build: the meetings wait for the model of the beta (T-06).
fun enginesForBuild(): Flow<List<Engine>> = emptyFlow()
