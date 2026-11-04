package com.example.notitia.ui.search

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.assertNotNull

// The fakes of these tests never suspend, so no test dispatcher is needed.
internal fun <T> runNow(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return assertNotNull(result, "the block suspended").getOrThrow()
}
