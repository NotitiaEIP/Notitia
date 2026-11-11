package com.example.notitia.meeting

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A meeting as the phone keeps it: what every screen reads, and what a .notitia file holds.
@Serializable
data class Meeting(
    val id: String,
    val startedAt: Instant,
    val duration: Duration,
    // When and where it last changed, so two devices can be reconciled later (E10).
    val modifiedAt: Instant,
    val device: String,
    // null until the user names it: the list then shows the recording's date.
    val title: String? = null,
    // Named by the user, meeting by meeting: no voiceprint links them across meetings (product rule 4).
    val speakers: List<String> = emptyList(),
    val transcript: List<TranscriptLine> = emptyList(),
    val minutes: MinutesState,
)

// Minutes not written yet have no action to change.
fun Meeting.withAction(index: Int, transform: (MinutesAction) -> MinutesAction): Meeting {
    val written = (minutes as? MinutesState.Written)?.minutes ?: return this
    val actions = written.actions.mapIndexed { i, action -> if (i == index) transform(action) else action }
    return copy(minutes = MinutesState.Written(written.copy(actions = actions)))
}

// An action is known by its place in the minutes of its meeting.
data class ActionId(val meetingId: String, val index: Int) {
    // Lazy lists keep their keys in an Android Bundle, which takes a string but not this class.
    val key: String get() = "$meetingId/$index"
}

// text is null for a passage the engine could not transcribe.
@Serializable
data class TranscriptLine(val at: Duration, val text: String?)

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

    // No writer in this build: release builds have no gateway.
    @Serializable
    @SerialName("unavailable")
    data object Unavailable : MinutesState
}

@Serializable
data class MinutesAction(
    val text: String,
    // A first name, as said in the meeting: null when nobody was named.
    val owner: String?,
    val due: LocalDate?,
    val done: Boolean,
    // null when the minutes cannot point to the sentence: the sheet then shows only the meeting.
    val source: ActionSource? = null,
)

// A name typed with spaces around it, or left blank, is no owner.
fun MinutesAction.ownedBy(name: String?): MinutesAction = copy(owner = name?.trim()?.takeIf { it.isNotEmpty() })

// The sentence as transcribed, never as the model rewrote it. speaker is null until the voices are told apart (E2).
@Serializable
data class ActionSource(val quote: String, val speaker: String?, val at: Duration)
