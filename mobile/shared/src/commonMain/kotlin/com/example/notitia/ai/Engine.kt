package com.example.notitia.ai

import com.example.notitia.meeting.Milliseconds
import kotlin.time.Duration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// An engine this app runs for the core: the gateway in debug, then Gemini Nano and Apple's. The core chooses among
// those offered, and calls run on a thread of its own, which waits for the reply (docs/adr/0010-moteurs.md). An engine
// speaks these types; the JSON that crosses the bridge, core/tests/fixtures/bridge/engines.json, is the session's.
interface Engine {
    val offered: OfferedEngine

    // Blocks until done, and never throws: a failure is a reply. Unavailable means not now: the core loses nothing,
    // and the app offers its engines again a little later.
    fun run(request: EngineRequest, sound: ByteArray): EngineReply
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface OfferedEngine {
    // Unique among those offered, and changed with the model.
    val name: String

    // What it is given leaves the phone (product rule 1): the core takes it last.
    val remote: Boolean

    // longest: the longest sound one call takes.
    @Serializable
    @SerialName("speech")
    data class Speech(
        override val name: String,
        override val remote: Boolean,
        @SerialName("longestMs") @Serializable(Milliseconds::class) val longest: Duration,
    ) : OfferedEngine

    // In tokens: what one call holds, the reply included, and the longest reply.
    @Serializable
    @SerialName("text")
    data class Text(
        override val name: String,
        override val remote: Boolean,
        val contextTokens: Int,
        val replyTokens: Int,
    ) : OfferedEngine

    @Serializable
    @SerialName("embedding")
    data class Embedding(override val name: String, override val remote: Boolean) : OfferedEngine
}

@Serializable
sealed interface EngineRequest {
    // The sound comes beside it, 16-bit little-endian at 16 kHz: one window, no longer than the engine said. previous
    // is the end of what was heard before it, which may keep a model on track.
    @Serializable
    @SerialName("transcribe")
    data class Transcribe(val language: String, val previous: String) : EngineRequest

    @Serializable
    @SerialName("complete")
    data class Complete(val system: String, val prompt: String, val replyTokens: Int) : EngineRequest

    @Serializable
    @SerialName("embed")
    data class Embed(val texts: List<String>) : EngineRequest
}

@Serializable
sealed interface EngineReply {
    @Serializable
    @SerialName("heard")
    data class Heard(val segments: List<Said>) : EngineReply

    @Serializable
    @SerialName("written")
    data class Written(val text: String) : EngineReply

    @Serializable
    @SerialName("embedded")
    data class Embedded(val vectors: List<List<Float>>) : EngineReply

    // Not now: a model downloading, a gateway out of reach. Nothing is marked, the work waits.
    @Serializable
    @SerialName("unavailable")
    data object Unavailable : EngineReply

    // This input failed: the work moves on without it.
    @Serializable
    @SerialName("failed")
    data object Failed : EngineReply
}

// What was said and when, from the start of the window.
@Serializable
data class Said(
    @SerialName("fromMs") @Serializable(Milliseconds::class) val from: Duration,
    @SerialName("toMs") @Serializable(Milliseconds::class) val to: Duration,
    val text: String,
)
