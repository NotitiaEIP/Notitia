package com.example.notitia.bridge

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

// Runs what is dispatched to it only when the test says so, on the test's thread. As on a real dispatcher, nothing
// runs inside the call that dispatched it: a listener of the core never finds itself calling the core back.
internal class ManualDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    // true when anything ran, what it dispatched in turn included.
    fun runAll(): Boolean {
        var ran = false
        while (queue.isNotEmpty()) {
            queue.removeFirst().run()
            ran = true
        }
        return ran
    }
}
