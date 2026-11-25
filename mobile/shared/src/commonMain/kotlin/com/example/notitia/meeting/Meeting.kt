package com.example.notitia.meeting

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// A meeting as every screen reads it, and as the Rust core tells it: the names are those of version 2 of the
// .notitia format (ADR 0003). Only the core changes and writes one.
@Serializable
data class Meeting(
    val id: String,
    val startedAt: Instant,
    @SerialName("durationMs") @Serializable(Milliseconds::class) val duration: Duration,
    // How far the core has heard it, while it does: null once the transcript is whole.
    @SerialName("transcribedMs") @Serializable(Milliseconds::class) val transcribed: Duration? = null,
    // When and where it last changed, so two devices can be reconciled later (E10).
    val modifiedAt: Instant,
    val device: String,
    // null until the user names it: the list then shows the recording's date.
    val title: String? = null,
    // Named by the user, meeting by meeting: no voiceprint links them across meetings (product rule 4).
    val speakers: List<String> = emptyList(),
    val minutes: MinutesState,
    // Empty in the list of meetings, which the core tells without their lines: Meetings.transcript reads them.
    val transcript: List<TranscriptLine> = emptyList(),
)

// The format counts time in whole milliseconds, which every language reads.
object Milliseconds : KSerializer<Duration> {
    override val descriptor = PrimitiveSerialDescriptor("Milliseconds", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeLong(value.inWholeMilliseconds)

    override fun deserialize(decoder: Decoder): Duration = decoder.decodeLong().milliseconds
}

// An action is known by its identifier in the minutes of its meeting.
data class ActionId(val meetingId: String, val actionId: String) {
    // Lazy lists keep their keys in an Android Bundle, which takes a string but not this class. A reminder already
    // scheduled on a phone opens its action by this key: its shape cannot change.
    val key: String get() = "$meetingId/$actionId"
}

// text is null for a passage the engine could not transcribe, speaker until the voices are told apart (E2).
@Serializable
data class TranscriptLine(
    @SerialName("atMs") @Serializable(Milliseconds::class) val at: Duration,
    val text: String?,
    val speaker: String? = null,
)

@Serializable
data class WrittenMinutes(
    val summary: String,
    val decisions: List<String>,
    val actions: List<MinutesAction>,
)

@Serializable
sealed interface MinutesState {
    @Serializable
    @SerialName("writing")
    data object Writing : MinutesState

    @Serializable
    @SerialName("written")
    data class Written(val minutes: WrittenMinutes) : MinutesState

    @Serializable
    @SerialName("failed")
    data object Failed : MinutesState

    // Left by an app older than the core's writer, which had none: never written now, asked again by rewrite.
    @Serializable
    @SerialName("unavailable")
    data object Unavailable : MinutesState
}

@Serializable
data class MinutesAction(
    // Drawn by the core when the minutes are written. A meeting kept before the core knows an action by its rank.
    val id: String,
    val text: String,
    // A first name, as said in the meeting: null when nobody was named.
    val owner: String?,
    val due: LocalDate?,
    val done: Boolean,
    // null when the minutes cannot point to the sentence: the sheet then shows only the meeting.
    val source: ActionSource? = null,
    // Asked by the user on the action's sheet: a notification the day before it is due.
    val reminder: Boolean = false,
)

// The sentence as transcribed, never as the model rewrote it. speaker is null until the voices are told apart (E2).
@Serializable
data class ActionSource(
    val quote: String,
    val speaker: String?,
    @SerialName("atMs") @Serializable(Milliseconds::class) val at: Duration,
)
