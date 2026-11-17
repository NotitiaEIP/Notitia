package com.example.notitia.bridge

import com.example.notitia.core.Bridge
import com.example.notitia.core.BridgeException
import com.example.notitia.core.BridgeListener
import com.example.notitia.core.InternalException
import com.example.notitia.core.uniffiEnsureInitialized

// The core as Android reaches it: through the Kotlin that UniFFI generates, which loads the library on first use.
class RustCore : NotitiaCore {
    // Set once, by open, before any other call.
    @Volatile
    private var bridge: Bridge? = null

    override fun open(directory: String, listener: CoreListener): CoreFailure? = call {
        bridge = Bridge.open(
            directory,
            object : BridgeListener {
                override fun onEvent(event: String) = listener.onEvent(event)
            },
        )
        null
    }.failure

    override fun meeting(id: String): CoreReply = opened { it.meeting(id) }

    override fun apply(change: String): CoreFailure? = opened {
        it.apply(change)
        null
    }.failure

    private inline fun opened(ask: (Bridge) -> String?): CoreReply {
        val bridge = bridge ?: return CoreReply(text = null, failure = CoreFailure.Broken)
        return call { ask(bridge) }
    }

    // The one way into the core. UniFFI generates a check of the library against the generated Kotlin, but on
    // Android nothing runs it: a library that does not match would be called anyway, and corrupt memory instead of
    // failing.
    private inline fun call(ask: () -> String?): CoreReply = try {
        uniffiEnsureInitialized()
        CoreReply(text = ask(), failure = null)
    } catch (failure: BridgeException) {
        CoreReply(
            text = null,
            failure = when (failure) {
                is BridgeException.NotFound -> CoreFailure.NotFound
                is BridgeException.Unreadable -> CoreFailure.Unreadable
                is BridgeException.Invalid -> CoreFailure.Invalid
            },
        )
    } catch (_: InternalException) {
        CoreReply(text = null, failure = CoreFailure.Broken)
    }
}
