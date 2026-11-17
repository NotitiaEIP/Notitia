package com.example.notitia.bridge

import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.Milliseconds
import com.example.notitia.meeting.MinutesDraft
import com.example.notitia.meeting.TranscriptLine
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The text that crosses the bridge, as the core reads and writes it: Change and Event in core/src. The examples of
// core/tests/fixtures/bridge are read by the tests of both sides, so a name that drifts breaks a test, not a phone.
// A field left out and a null field mean the same, and a field this app does not know is skipped: the core keeps it.
internal val CoreJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
internal sealed interface Change {
    @Serializable
    @SerialName("edit")
    data class Edit(val meeting: String, val edit: MeetingEdit) : Change

    @Serializable
    @SerialName("delete")
    data class Delete(val meetings: Set<String>) : Change

    // Where the recorder stands: the core dates it, signs it, and keeps what the user gave meanwhile.
    @Serializable
    @SerialName("record")
    data class Record(
        val id: String,
        val startedAt: Instant,
        @SerialName("durationMs") @Serializable(Milliseconds::class) val duration: Duration,
        val transcript: List<TranscriptLine> = emptyList(),
        val minutes: MinutesDraft,
    ) : Change

    // Samples: changed like the others for as long as the app runs, never written.
    @Serializable
    @SerialName("lend")
    data class Lend(val meetings: List<Meeting>) : Change
}

@Serializable
internal sealed interface MeetingEdit {
    @Serializable
    @SerialName("rename")
    data class Rename(val title: String) : MeetingEdit

    @Serializable
    @SerialName("setDone")
    data class SetDone(val action: String, val done: Boolean) : MeetingEdit

    @Serializable
    @SerialName("setOwner")
    data class SetOwner(val action: String, val owner: String?) : MeetingEdit

    @Serializable
    @SerialName("setDue")
    data class SetDue(val action: String, val due: LocalDate?) : MeetingEdit

    @Serializable
    @SerialName("setReminder")
    data class SetReminder(val action: String, val on: Boolean) : MeetingEdit
}

// A meeting comes without its lines.
@Serializable
internal sealed interface CoreEvent {
    // Every meeting, the latest first.
    @Serializable
    @SerialName("listed")
    data class Listed(val meetings: List<Meeting>) : CoreEvent

    // New when the app does not know it yet.
    @Serializable
    @SerialName("changed")
    data class Changed(val meeting: Meeting) : CoreEvent

    @Serializable
    @SerialName("removed")
    data class Removed(val meetings: Set<String>) : CoreEvent

    // The meetings whose file is behind what the app shows. Empty once every one is written.
    @Serializable
    @SerialName("unsaved")
    data class Unsaved(val meetings: Set<String>) : CoreEvent
}
