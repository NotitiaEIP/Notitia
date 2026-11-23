package com.example.notitia.bridge

import com.example.notitia.core.Bridge
import com.example.notitia.core.BridgeEngines
import com.example.notitia.core.BridgeException
import com.example.notitia.core.BridgeListener
import com.example.notitia.core.InternalException
import com.example.notitia.core.uniffiEnsureInitialized

// The core as Android reaches it: through the Kotlin that UniFFI generates, which loads the library on first use.
class RustCore : NotitiaCore {
    // Set once, by open, before any other call. The microphone reads it from its own thread.
    @Volatile
    private var bridge: Bridge? = null

    override fun open(directory: String, key: ByteArray?, listener: CoreListener): CoreFailure? = call {
        bridge = Bridge.open(
            directory,
            key,
            object : BridgeListener {
                override fun onEvent(event: String) = listener.onEvent(event)
            },
        )
    }.failure

    override fun meeting(id: String): CoreReply = opened { it.meeting(id) }.let { CoreReply(it.value, it.failure) }

    override fun apply(request: String): CoreFailure? = opened { it.apply(request) }.failure

    // Straight to the core, never behind a call that writes: nothing goes back to the microphone, not even a failure,
    // which the recorder tells as its state.
    override fun hear(track: Int, pcm: ByteArray) {
        val bridge = bridge ?: return
        try {
            bridge.hear(track.coerceIn(0, UByte.MAX_VALUE.toInt()).toUByte(), pcm)
        } catch (_: Exception) {
        }
    }

    override fun sound(id: String, fromMs: Long, lengthMs: Long): CoreSound =
        opened { it.sound(id, fromMs.coerceAtLeast(0).toULong(), lengthMs.coerceAtLeast(0).toULong()) }
            .let { CoreSound(it.value, it.failure) }

    override fun offer(offered: String, engines: CoreEngines): CoreFailure? {
        val guarded = neverThrowing(engines)
        return opened {
            it.offer(
                offered,
                object : BridgeEngines {
                    override fun run(engine: String, request: String, sound: ByteArray) =
                        guarded.run(engine, request, sound)
                },
            )
        }.failure
    }

    private class Answer<T>(val value: T?, val failure: CoreFailure?)

    private inline fun <T> opened(ask: (Bridge) -> T): Answer<T> {
        val bridge = bridge ?: return Answer(null, CoreFailure.Broken)
        return call { ask(bridge) }
    }

    // The one way into the core. UniFFI generates a check of the library against the generated Kotlin, but on
    // Android nothing runs it: a library that does not match would be called anyway, and corrupt memory instead of
    // failing.
    private inline fun <T> call(ask: () -> T): Answer<T> = try {
        uniffiEnsureInitialized()
        Answer(ask(), null)
    } catch (failure: BridgeException) {
        Answer(
            null,
            when (failure) {
                is BridgeException.NotFound -> CoreFailure.NotFound
                is BridgeException.Unreadable -> CoreFailure.Unreadable
                is BridgeException.Invalid -> CoreFailure.Invalid
            },
        )
    } catch (_: InternalException) {
        Answer(null, CoreFailure.Broken)
    }
}
