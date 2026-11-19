package com.example.notitia.bridge

import com.example.notitia.meeting.ActionDraft
import com.example.notitia.meeting.ActionSource
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesDraft
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.TranscriptLine
import com.example.notitia.recording.BYTES_PER_SAMPLE
import com.example.notitia.recording.SAMPLE_RATE
import com.example.notitia.recording.rms
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

private const val SAID = "Self-check"
private const val TITLE = "Checked"

// Made up, so the marker and the folder say nothing of a real meeting. An action with every field, so each kind of
// value crosses both ways: a date, a time in the audio, a text that may be absent.
private val minutes = MinutesDraft.Written(
    summary = SAID,
    decisions = listOf(SAID),
    actions = listOf(ActionDraft(SAID, owner = SAID, due = LocalDate(2026, 11, 20), ActionSource(SAID, speaker = null, Duration.ZERO))),
)

// A second of a note, as a microphone reads it: sound, so what comes back can be told from silence.
private val note = ByteArray(BYTES_PER_SAMPLE * SAMPLE_RATE).also { pcm ->
    for (at in 0 until SAMPLE_RATE) {
        val sample = (sin(2 * PI * 440 * at / SAMPLE_RATE) * 8_000).toInt()
        pcm[BYTES_PER_SAMPLE * at] = sample.toByte()
        pcm[BYTES_PER_SAMPLE * at + 1] = (sample shr 8).toByte()
    }
}

// Proves the bridge where the app runs: a made-up second is heard, kept, corrected, read and played back, each step
// answered by its event, and every kind of value crosses both ways, bytes included. On the iPhone it is the only
// proof there is: the CI runs it on a simulator, since the Swift that holds the core is written on machines that
// cannot compile it.
class CoreSelfCheck(
    private val core: NotitiaCore,
    private val directory: Path = Path(SystemTemporaryDirectory, "core-self-check"),
    private val files: FileSystem = SystemFileSystem,
    // The recorder answers from its own thread: past this, it is stuck.
    private val patience: Duration = 10.seconds,
) {
    // As they crossed, from the thread that asked or from the recorder's: read in order, after each step.
    private val events = Channel<String>(Channel.UNLIMITED)

    // Fixed words, safe to log: the name of a step, never what a meeting holds.
    fun run(): String = firstFailure()?.let { "core bridge: failed at $it" } ?: "core bridge: ok"

    private fun firstFailure(): String? = try {
        clear()
        when {
            // Made up too: the key of a folder cleared before and after.
            core.open(directory.toString(), ByteArray(32) { it.toByte() }) { events.trySend(it) } != null -> "open"
            next { it is CoreEvent.Listed } != CoreEvent.Listed(emptyList()) -> "list"
            else -> when (val meeting = recorded()) {
                null -> "record"
                else -> afterRecording(meeting)
            }
        }
    } catch (_: Exception) {
        "exception"
    } finally {
        clear()
    }

    // A second heard, then stopped: the meeting the recorder made, once its audio is whole.
    private fun recorded(): String? {
        if (send(Command.Start(tracks = 1, offsetMinutes = 60)) != null) return null
        core.hear(0, note)
        val meeting = (next { it is CoreEvent.Recorder && it.meeting != null } as? CoreEvent.Recorder)?.meeting
        if (meeting == null || send(Command.Stop) != null) return null
        return meeting.takeIf { next { it is CoreEvent.Recorder && it.state == CoreEvent.Recorder.Phase.Idle } != null }
    }

    private fun afterRecording(meeting: String): String? {
        val action = transcribed(meeting)
        return when {
            action == null -> "transcribed"
            send(Change.Edit(meeting, MeetingEdit.Rename(TITLE))) != null || changed(meeting)?.title != TITLE -> "rename"
            send(Change.Edit(meeting, MeetingEdit.SetDone(action, done = true))) != null ||
                changed(meeting)?.actions()?.singleOrNull()?.done != true -> "tick"
            read(meeting)?.takeIf { it.title == TITLE }?.transcript?.singleOrNull()?.text != SAID -> "read"
            !core.sound(meeting, fromMs = 0, lengthMs = 1_000).isTheNote() -> "sound"
            core.meeting("$meeting-missing").failure != CoreFailure.NotFound -> "missing meeting"
            core.apply("{") != CoreFailure.Invalid -> "refused request"
            send(Change.Delete(setOf(meeting))) != null ||
                next { it is CoreEvent.Removed } != CoreEvent.Removed(setOf(meeting)) -> "delete"
            else -> null
        }
    }

    // The lines and the minutes the app hands over: the identifier of the action is the core's to draw.
    private fun transcribed(meeting: String): String? {
        if (send(Change.Transcribed(meeting, listOf(TranscriptLine(Duration.ZERO, SAID)), minutes)) != null) return null
        val action = changed(meeting)?.actions()?.singleOrNull()
        return action?.takeIf { it.id.isNotEmpty() && it.due == LocalDate(2026, 11, 20) }?.id
    }

    private fun send(request: CoreRequest): CoreFailure? =
        core.apply(CoreJson.encodeToString(CoreRequest.serializer(), request))

    // The events before the one awaited only led to it.
    private fun next(matches: (CoreEvent) -> Boolean): CoreEvent? = runBlocking {
        withTimeoutOrNull(patience) {
            var found: CoreEvent? = null
            while (found == null) found = readEvent(events.receive())?.takeIf(matches)
            found
        }
    }

    private fun changed(meeting: String): Meeting? =
        (next { it is CoreEvent.Changed && it.meeting.id == meeting } as? CoreEvent.Changed)?.meeting

    private fun read(meeting: String): Meeting? = core.meeting(meeting).text?.let { CoreJson.decodeFromString<Meeting>(it) }

    private fun Meeting.actions() = (minutes as? MinutesState.Written)?.minutes?.actions

    // Played back as it was heard: a second, as loud as a voice. The codec keeps the sound, not every sample.
    private fun CoreSound.isTheNote(): Boolean = pcm?.takeIf { it.size == note.size }?.let { rms(it) > 2_000 } == true

    private fun clear() {
        if (!files.exists(directory)) return
        files.list(directory).forEach { files.delete(it, mustExist = false) }
        files.delete(directory, mustExist = false)
    }
}
