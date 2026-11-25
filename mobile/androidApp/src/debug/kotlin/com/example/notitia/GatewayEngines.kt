package com.example.notitia

import com.example.notitia.ai.Engine
import com.example.notitia.ai.EngineReply
import com.example.notitia.ai.EngineRequest
import com.example.notitia.ai.OfferedEngine
import com.example.notitia.ai.Said
import com.example.notitia.recording.BYTES_PER_MS
import com.example.notitia.recording.pcmToWav
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.json.JSONException
import org.json.JSONObject

// The gateway lent to the core as an engine of each kind. Both are remote: what they are given leaves the phone, so
// the core takes them last (ADR 0010), and only debug builds lend them. Out of reach, they say so, and the core loses
// nothing: the app offers them again a little later.
fun gatewayEngines(gateway: Gateway): List<Engine> = listOf(GatewayEar(gateway), GatewayPen(gateway))

private class GatewayEar(private val gateway: Gateway) : Engine {
    // What Whisper hears in one call.
    override val offered = OfferedEngine.Speech("gateway-transcribe", remote = true, longest = 30.seconds)

    override fun run(request: EngineRequest, sound: ByteArray): EngineReply {
        val asked = request as? EngineRequest.Transcribe ?: return EngineReply.Failed
        return when (val outcome = gateway.transcribe(pcmToWav(sound), asked.language, asked.previous)) {
            is Outcome.Done -> heard(outcome.value, (sound.size / BYTES_PER_MS).milliseconds)
            Outcome.Unreachable -> EngineReply.Unavailable
            Outcome.Refused -> EngineReply.Failed
        }
    }
}

private class GatewayPen(private val gateway: Gateway) : Engine {
    // The least of the models the gateway may answer with, so an hour goes through the slices a phone's model needs.
    override val offered = OfferedEngine.Text("gateway-write", remote = true, contextTokens = 8_192, replyTokens = 1_024)

    override fun run(request: EngineRequest, sound: ByteArray): EngineReply {
        val asked = request as? EngineRequest.Complete ?: return EngineReply.Failed
        return when (val outcome = gateway.complete(asked.system, asked.prompt, asked.replyTokens)) {
            is Outcome.Done -> EngineReply.Written(outcome.value)
            Outcome.Unreachable -> EngineReply.Unavailable
            Outcome.Refused -> EngineReply.Failed
        }
    }
}

// Each sentence with its times, so an action points to the one it came from. A server that gives no sentences gives
// its text, said over the whole window.
private fun heard(reply: JSONObject, window: Duration): EngineReply = try {
    val segments = reply.optJSONArray("segments")
    val said = if (segments == null) {
        listOf(Said(Duration.ZERO, window, reply.getString("text")))
    } else {
        List(segments.length()) { segments.getJSONObject(it) }.map {
            Said(it.getDouble("start").seconds, it.getDouble("end").seconds, it.getString("text"))
        }
    }
    EngineReply.Heard(said)
} catch (_: JSONException) {
    EngineReply.Failed
}
