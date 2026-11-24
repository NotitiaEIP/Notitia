package com.example.notitia.bridge

import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.Milliseconds
import com.example.notitia.meeting.MinutesDraft
import com.example.notitia.meeting.RecorderState
import com.example.notitia.meeting.RecordingProblem
import com.example.notitia.meeting.TranscriptLine
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The text that crosses the bridge, as the core reads and writes it: Request and Event in core/src/session.rs. The
// examples of core/tests/fixtures/bridge are read by the tests of both sides, so a name that drifts breaks a test, not
// a phone. A field left out and a null field mean the same, and a field this app does not know is skipped: the core
// keeps it.
internal val CoreJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

// An event this app cannot read is skipped, never thrown back into the core. Catching IllegalArgumentException covers
// SerializationException.
internal fun readEvent(event: String): CoreEvent? = try {
    CoreJson.decodeFromString<CoreEvent>(event)
} catch (_: IllegalArgumentException) {
    null
}

// What the app asks of the core: a change of the meetings, or a command of the recorder.
@Serializable
internal sealed interface CoreRequest

@Serializable
internal sealed interface Change : CoreRequest {
    @Serializable
    @SerialName("edit")
    data class Edit(val meeting: String, val edit: MeetingEdit) : Change

    @Serializable
    @SerialName("delete")
    data class Delete(val meetings: Set<String>) : Change

    // Minutes that failed or were never written: owed again, to the core's writer.
    @Serializable
    @SerialName("rewrite")
    data class Rewrite(val meeting: String) : Change

    // What the app transcribed of a meeting the recorder made, the whole of it each time, and its minutes once
    // written. Changes nothing of a meeting the core does not know.
    @Serializable
    @SerialName("transcribed")
    data class Transcribed(
        val meeting: String,
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

// The state comes back as the recorder event, never as an answer.
@Serializable
internal sealed interface Command : CoreRequest {
    // One track on a phone. The offset is how far ahead of UTC the device is: the core never asks the system.
    @Serializable
    @SerialName("start")
    data class Start(val tracks: Int, val offsetMinutes: Int) : Command

    @Serializable
    @SerialName("pause")
    data object Pause : Command

    @Serializable
    @SerialName("resume")
    data object Resume : Command

    @Serializable
    @SerialName("stop")
    data object Stop : Command

    // The microphone gives no more sound.
    @Serializable
    @SerialName("cut")
    data class Cut(val problem: RecordingProblem) : Command

    // The failure was shown.
    @Serializable
    @SerialName("dismiss")
    data object Dismiss : Command
}

// A meeting comes without its lines.
@Serializable
internal sealed interface CoreEvent {
    // Every meeting, the latest first.
    @Serializable
    @SerialName("listed")
    data class Listed(val meetings: List<Meeting>) : CoreEvent

    // New when the app does not know it yet. at is its place in the list once changed: the core alone sorts.
    @Serializable
    @SerialName("changed")
    data class Changed(val meeting: Meeting, val at: Int) : CoreEvent

    @Serializable
    @SerialName("removed")
    data class Removed(val meetings: Set<String>) : CoreEvent

    // The meetings whose file is behind what the app shows. Empty once every one is written.
    @Serializable
    @SerialName("unsaved")
    data class Unsaved(val meetings: Set<String>) : CoreEvent

    // The whole state of the recorder, told at each change and every 100 ms of sound. Each state has its own
    // fields: a problem when it failed, the rest while it is active, the level while it records. meeting is made at
    // the first sound.
    @Serializable
    @SerialName("recorder")
    data class Recorder(
        val state: Phase,
        val problem: RecordingProblem? = null,
        val meeting: String? = null,
        val startedAt: Instant? = null,
        @SerialName("recordedMs") @Serializable(Milliseconds::class) val recorded: Duration = Duration.ZERO,
        val level: Float = 0f,
    ) : CoreEvent {
        // As the screens read it. null for a state without the fields it needs, which the core never tells: skipped,
        // like any event this app cannot read.
        fun toState(): RecorderState? = when (state) {
            Phase.Idle -> RecorderState.Idle
            Phase.Failed -> problem?.let(RecorderState::Failed)
            Phase.Recording -> startedAt?.let { RecorderState.Recording(it, recorded, level, meeting) }
            Phase.Paused -> startedAt?.let { RecorderState.Paused(it, recorded, meeting) }
            Phase.Interrupted -> startedAt?.let { RecorderState.Interrupted(it, recorded, meeting) }
        }

        @Serializable
        enum class Phase {
            @SerialName("idle")
            Idle,

            @SerialName("failed")
            Failed,

            @SerialName("recording")
            Recording,

            @SerialName("paused")
            Paused,

            @SerialName("interrupted")
            Interrupted,
        }
    }
}
