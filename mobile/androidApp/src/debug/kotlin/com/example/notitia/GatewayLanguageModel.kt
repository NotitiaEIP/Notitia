package com.example.notitia

import com.example.notitia.ai.LanguageModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_TOKENS = 1024

// The gateway as the model of the search, until the search moves into the core (T-60): a failure is thrown, as the
// answer engine expects.
class GatewayLanguageModel(private val gateway: Gateway) : LanguageModel {
    override suspend fun embed(texts: List<String>): List<List<Double>> =
        withContext(Dispatchers.IO) { gateway.embed(texts).orThrow() }

    override suspend fun complete(system: String, prompt: String): String =
        withContext(Dispatchers.IO) { gateway.complete(system, prompt, MAX_TOKENS).orThrow() }
}

private fun <T> Outcome<T>.orThrow(): T = when (this) {
    is Outcome.Done -> value
    Outcome.Unreachable -> throw IOException("the gateway is out of reach")
    Outcome.Refused -> error("the gateway refused")
}
