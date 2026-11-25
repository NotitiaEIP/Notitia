package com.example.notitia.bridge

import com.example.notitia.ai.Engine
import com.example.notitia.ai.EngineReply
import com.example.notitia.ai.EngineRequest
import com.example.notitia.ai.OfferedEngine
import kotlinx.serialization.builtins.ListSerializer

// The engines of this app as the core takes them: the offer it reads, and the one callback through which it runs any
// of them, on a thread of its own (core/tests/fixtures/bridge/engines.json). A request this app cannot read, or an
// engine it does not lend, fails: nothing throws back into the core.
internal class Lending(engines: List<Engine>, private val unavailable: () -> Unit = {}) : CoreEngines {
    private val byName = engines.associateBy { it.offered.name }

    val offer: String = CoreJson.encodeToString(ListSerializer(OfferedEngine.serializer()), engines.map { it.offered })

    override fun run(engine: String, request: String, sound: ByteArray): String {
        val (lent, asked) = byName[engine] to readRequest(request)
        val reply = if (lent == null || asked == null) EngineReply.Failed else lent.run(asked, sound)
        if (reply == EngineReply.Unavailable) unavailable()
        return CoreJson.encodeToString(EngineReply.serializer(), reply)
    }
}

private fun readRequest(request: String): EngineRequest? = try {
    CoreJson.decodeFromString<EngineRequest>(request)
} catch (_: IllegalArgumentException) {
    null
}
