package com.example.notitia.bridge

import com.example.notitia.ai.Engine
import com.example.notitia.ai.EngineReply
import com.example.notitia.ai.EngineRequest
import com.example.notitia.ai.OfferedEngine
import com.example.notitia.ai.Said
import com.example.notitia.meeting.DueGroup
import com.example.notitia.meeting.Grouped
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.Placed
import com.example.notitia.recording.BYTES_PER_SAMPLE
import com.example.notitia.recording.SAMPLE_RATE
import com.example.notitia.recording.rms
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.io.IOException
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

private const val SAID = "Self-check"
private const val TITLE = "Checked"
private const val EAR = "self-check-ear"
private val DUE = LocalDate(2026, 11, 20)

// Made up, so the marker and the folder say nothing of a real meeting. The core calls them on a thread of its own:
// every kind of value crosses that way too, the sound as bytes. The pen writes an action with every field, a date and
// the time of the line it read, as a model does, so the action points to its sentence.
private object Ear : Engine {
    override val offered = OfferedEngine.Speech(EAR, remote = false, longest = 30.seconds)

    override fun run(request: EngineRequest, sound: ByteArray): EngineReply =
        if (request is EngineRequest.Transcribe && sound.isNotEmpty()) {
            EngineReply.Heard(listOf(Said(Duration.ZERO, 900.milliseconds, SAID)))
        } else {
            EngineReply.Failed
        }
}

private object Pen : Engine {
    override val offered = OfferedEngine.Text("self-check-pen", remote = false, contextTokens = 4_096, replyTokens = 1_024)

    override fun run(request: EngineRequest, sound: ByteArray): EngineReply {
        val prompt = (request as? EngineRequest.Complete)?.prompt ?: return EngineReply.Failed
        val time = prompt.substringAfter("[").substringBefore("]")
        return EngineReply.Written("SUMMARY: $SAID\nDECISION: $SAID | $time\nACTION: $SAID | $SAID | $DUE | $time")
    }
}

private fun tone(seconds: Int, loudness: Double): ByteArray = ByteArray(BYTES_PER_SAMPLE * SAMPLE_RATE * seconds).also { pcm ->
    for (at in 0 until SAMPLE_RATE * seconds) {
        val sample = (sin(2 * PI * 440 * at / SAMPLE_RATE) * loudness).toInt()
        pcm[BYTES_PER_SAMPLE * at] = sample.toByte()
        pcm[BYTES_PER_SAMPLE * at + 1] = (sample shr 8).toByte()
    }
}

// A second of a note, as a microphone reads it: sound, so what comes back can be told from silence.
private val note = tone(seconds = 1, loudness = 8_000.0)

// The note said between two silences, so the core hears it as speech against the noise it learns from them.
private val spoken = tone(seconds = 1, loudness = 0.0) + note + note + tone(seconds = 3, loudness = 0.0)

// Proves the bridge where the app runs: engines of this app are lent, a made-up note is heard, transcribed and written
// up by the core's thread through them, then corrected, placed on a board, read and played back, each step answered
// by its event, and every kind of value crosses both ways, bytes included. On the iPhone it is the only proof there
// is: the CI runs it on a simulator, since the Swift that holds the core is written on machines that cannot compile it.
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
            !lent() -> "offer"
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

    // Chosen by the core, which tells so.
    private fun lent(): Boolean {
        val lending = Lending(listOf(Ear, Pen))
        if (core.offer(lending.offer, lending) != null) return false
        return next { it is CoreEvent.Engines && it.speech.firstOrNull()?.name == EAR } != null
    }

    // Heard, then stopped: the meeting the recorder made, once its audio is whole.
    private fun recorded(): String? {
        if (send(Command.Start(tracks = 1, offsetMinutes = 60)) != null) return null
        core.hear(0, spoken)
        val meeting = (next { it is CoreEvent.Recorder && it.meeting != null } as? CoreEvent.Recorder)?.meeting
        if (meeting == null || send(Command.Stop) != null) return null
        return meeting.takeIf { next { it is CoreEvent.Recorder && it.state == CoreEvent.Recorder.Phase.Idle } != null }
    }

    private fun afterRecording(meeting: String): String? {
        val action = written(meeting)
        return when {
            action == null -> "minutes"
            send(Change.Edit(meeting, MeetingEdit.Rename(TITLE))) != null || told(meeting) { it.title == TITLE } == null -> "rename"
            !placedOnItsDay(meeting, action) -> "board"
            send(Change.Edit(meeting, MeetingEdit.SetDone(action, done = true))) != null ||
                told(meeting) { it.actions()?.singleOrNull()?.done == true } == null -> "tick"
            read(meeting)?.takeIf { it.title == TITLE }?.transcript?.singleOrNull()?.text != SAID -> "read"
            !core.sound(meeting, fromMs = 1_000, lengthMs = 1_000).isTheNote() -> "sound"
            core.meeting("$meeting-missing").failure != CoreFailure.NotFound -> "missing meeting"
            core.apply("{") != CoreFailure.Invalid -> "refused request"
            send(Change.Delete(setOf(meeting))) != null ||
                next { it is CoreEvent.Removed } != CoreEvent.Removed(setOf(meeting)) -> "delete"
            else -> null
        }
    }

    // Heard and written up by the core with the engines lent: the identifier of the action is the core's to draw, its
    // sentence the one heard. Told from the pipeline's thread, perhaps before the recorder said it had stopped, whose
    // event was awaited first: then they are read back.
    private fun written(meeting: String): String? {
        val done = read(meeting)?.takeIf { it.minutes is MinutesState.Written } ?: told(meeting) { it.minutes is MinutesState.Written }
        val action = done?.actions()?.singleOrNull()
        return action?.takeIf { it.id.isNotEmpty() && it.due == DUE && it.source?.quote == SAID }?.id
    }

    // Asked as a phone asks it, on the day the action is due: placed today, by its reference.
    private fun placedOnItsDay(meeting: String, action: String): Boolean {
        if (send(BoardAsk(DUE)) != null) return false
        val board = next { it is CoreEvent.Board } as? CoreEvent.Board
        return board?.groups == listOf(Grouped(DueGroup.Today, listOf(Placed(meeting, action, DueGroup.Today))))
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

    // The core tells from several threads: what an earlier step changed may still be told before the change awaited.
    private fun told(meeting: String, shows: (Meeting) -> Boolean): Meeting? =
        (next { it is CoreEvent.Changed && it.meeting.id == meeting && shows(it.meeting) } as? CoreEvent.Changed)?.meeting

    private fun read(meeting: String): Meeting? = core.meeting(meeting).text?.let { CoreJson.decodeFromString<Meeting>(it) }

    private fun Meeting.actions() = (minutes as? MinutesState.Written)?.minutes?.actions

    // Played back as it was heard: a second, as loud as a voice. The codec keeps the sound, not every sample.
    private fun CoreSound.isTheNote(): Boolean = pcm?.takeIf { it.size == note.size }?.let { rms(it) > 2_000 } == true

    // The core tells a change before it writes it: the check may end while it still writes in the folder, which then
    // cannot be removed. What it leaves goes with the next check, which clears the folder before opening the core.
    private fun clear() {
        try {
            if (!files.exists(directory)) return
            files.list(directory).forEach { files.delete(it, mustExist = false) }
            files.delete(directory, mustExist = false)
        } catch (_: IOException) {
        }
    }
}
