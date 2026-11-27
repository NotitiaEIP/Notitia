package com.example.notitia.bridge

import com.example.notitia.bridge.CoreEvent.Recorder.Phase
import com.example.notitia.ai.OfferedEngine
import com.example.notitia.meeting.KeepAudio
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.Placed
import com.example.notitia.meeting.RecordingProblem
import com.example.notitia.recording.BYTES_PER_MS
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

// When the fake recorder starts, and the meeting it makes at the first sound.
internal val fakeStart = Instant.fromEpochMilliseconds(1_794_303_000_000)
internal const val FAKE_RECORDED = "recorded-fake"

private val active = setOf(Phase.Recording, Phase.Paused, Phase.Interrupted)

// Stands for the Rust core in the tests of what talks to it: it keeps in memory the meetings it is given, records as
// far as these tests ask, and tells each change as the core does, before it returns. The rules themselves are tested
// in Rust, where they live. Like the core, it must never be called by its own listener, where the real one would
// wait for itself: here the test fails.
internal open class FakeCore(vararg stored: Meeting, private val opens: Boolean = true) : NotitiaCore {
    // What the screens asked. The session's own tidy-ups are counted apart.
    val applied = mutableListOf<CoreRequest>()
    val tidyUps = mutableListOf<Change.Tidy>()

    // Each offer as it crossed, the callback through which this core would run the engines.
    val offers = mutableListOf<Pair<List<OfferedEngine>, CoreEngines>>()

    // As the app gave it, before the app wiped its own copy.
    var key: ByteArray? = null
        private set
    private val kept = stored.associateBy { it.id }.toMutableMap()
    private val sounds = mutableMapOf<String, ByteArray>()
    private var listener: CoreListener? = null
    private var telling = false
    private var recorder = CoreEvent.Recorder(Phase.Idle)
    private var keepAudio = KeepAudio.Month

    override fun open(directory: String, key: ByteArray?, listener: CoreListener): CoreFailure? {
        if (!opens) return CoreFailure.Unreadable
        this.key = key?.copyOf()
        this.listener = listener
        tell(CoreEvent.Listed(listed()))
        tell(CoreEvent.Settings(keepAudio))
        tellEngines(emptyList())
        return null
    }

    override fun meeting(id: String): CoreReply {
        outsideTheListener()
        return kept[id]?.let { CoreReply(CoreJson.encodeToString(it), failure = null) } ?: CoreReply(text = null, CoreFailure.NotFound)
    }

    override fun apply(request: String): CoreFailure? {
        outsideTheListener()
        val asked = try {
            CoreJson.decodeFromString<CoreRequest>(request)
        } catch (_: IllegalArgumentException) {
            return CoreFailure.Invalid
        }
        if (asked is Change.Tidy) tidyUps += asked else applied += asked
        when (asked) {
            is Change.Edit -> kept[asked.meeting]?.let { keep(it.edited(asked.edit)) }
            is Change.Delete -> {
                asked.meetings.forEach {
                    kept.remove(it)
                    sounds.remove(it)
                }
                tell(CoreEvent.Removed(asked.meetings))
            }
            is Change.Rewrite -> kept[asked.meeting]
                ?.takeIf { it.minutes == MinutesState.Failed || it.minutes == MinutesState.Unavailable }
                ?.let { keep(it.copy(minutes = MinutesState.Writing)) }
            is Change.Lend -> {
                asked.meetings.forEach { kept.getOrPut(it.id) { it } }
                tell(CoreEvent.Listed(listed()))
            }
            // Erasing audio is the core's rule, tested in Rust.
            is Change.Tidy -> Unit
            is Choice -> asked.keepAudio?.takeIf { it != keepAudio }?.let {
                keepAudio = it
                tell(CoreEvent.Settings(it))
            }
            is Command -> command(asked)
            is BoardAsk -> tell(board(asked))
        }
        return null
    }

    // Counted only while recording, as the recorder does: the meeting is made at the first sound.
    override fun hear(track: Int, pcm: ByteArray) {
        if (recorder.state != Phase.Recording) return
        val meeting = recorder.meeting ?: FAKE_RECORDED.also {
            tellRecorder(recorder.copy(meeting = it))
            keep(Meeting(it, fakeStart, Duration.ZERO, modifiedAt = fakeStart, device = "fake", minutes = MinutesState.Writing))
        }
        sounds[meeting] = sounds[meeting]?.plus(pcm) ?: pcm.copyOf()
        tellRecorder(recorder.copy(recorded = recorder.recorded + (pcm.size / BYTES_PER_MS).milliseconds))
    }

    override fun sound(id: String, fromMs: Long, lengthMs: Long): CoreSound {
        outsideTheListener()
        val pcm = sounds[id] ?: return CoreSound(pcm = null, CoreFailure.NotFound)
        val from = (fromMs * BYTES_PER_MS).coerceIn(0, pcm.size.toLong()).toInt()
        val until = (from + lengthMs.coerceIn(0, 60_000) * BYTES_PER_MS).coerceAtMost(pcm.size.toLong()).toInt()
        return CoreSound(pcm.copyOfRange(from, until), failure = null)
    }

    // Which engine the core chooses is tested in Rust, where the rule lives: here, the first of each kind.
    override fun offer(offered: String, engines: CoreEngines): CoreFailure? {
        outsideTheListener()
        val read = try {
            CoreJson.decodeFromString<List<OfferedEngine>>(offered)
        } catch (_: IllegalArgumentException) {
            return CoreFailure.Invalid
        }
        offers += read to engines
        tellEngines(read)
        return null
    }

    // The engines lent last, run as the core would, from its own side of the bridge.
    fun run(engine: String, request: String, sound: ByteArray = ByteArray(0)): String =
        offers.last().second.run(engine, request, sound)

    fun tell(event: CoreEvent) = tell(CoreJson.encodeToString(CoreEvent.serializer(), event))

    fun tell(event: String) {
        telling = true
        try {
            listener?.onEvent(event)
        } finally {
            telling = false
        }
    }

    private fun command(command: Command) {
        val now = recorder
        when (command) {
            is Command.Start -> when {
                now.state in active -> Unit
                key == null -> tellRecorder(CoreEvent.Recorder(Phase.Failed, problem = RecordingProblem.Storage))
                else -> tellRecorder(CoreEvent.Recorder(Phase.Recording, startedAt = fakeStart))
            }
            Command.Pause -> if (now.state == Phase.Recording) tellRecorder(now.copy(state = Phase.Paused, level = 0f))
            Command.Resume -> if (now.state == Phase.Paused || now.state == Phase.Interrupted) tellRecorder(now.copy(state = Phase.Recording))
            Command.Stop -> if (now.state in active) {
                now.meeting?.let { id -> kept[id]?.let { keep(it.copy(duration = now.recorded)) } }
                tellRecorder(CoreEvent.Recorder(Phase.Idle))
            }
            is Command.Cut -> if (now.state in active) {
                tellRecorder(
                    if (now.meeting != null) {
                        now.copy(state = Phase.Interrupted, level = 0f)
                    } else {
                        CoreEvent.Recorder(Phase.Failed, problem = command.problem)
                    },
                )
            }
            Command.Dismiss -> if (now.state == Phase.Failed) tellRecorder(CoreEvent.Recorder(Phase.Idle))
        }
    }

    private fun tellEngines(offered: List<OfferedEngine>) {
        fun about(kind: List<OfferedEngine>) = kind.map { EngineAbout(it.name, it.remote) }
        tell(
            CoreEvent.Engines(
                speech = about(offered.filterIsInstance<OfferedEngine.Speech>()),
                text = about(offered.filterIsInstance<OfferedEngine.Text>()),
                embedding = about(offered.filterIsInstance<OfferedEngine.Embedding>()),
            ),
        )
    }

    private fun tellRecorder(state: CoreEvent.Recorder) {
        recorder = state
        tell(state)
    }

    private fun outsideTheListener() = check(!telling) { "the core was called by its own listener: the real one would wait for itself" }

    // Where each action goes is the core's rule, tested in Rust: here, every one is to complete.
    private fun board(asked: BoardAsk): CoreEvent.Board {
        val placed = kept.values.flatMap { meeting ->
            (meeting.minutes as? MinutesState.Written)?.minutes?.actions.orEmpty().map { Placed(meeting.id, it.id) }
        }
        return CoreEvent.Board(asked.today, placed, emptyList(), emptyList(), placed.size, 0, emptyList(), emptyMap())
    }

    private fun listed() = kept.values.sortedByDescending { it.startedAt }.map { it.copy(transcript = emptyList()) }

    private fun keep(meeting: Meeting) {
        kept[meeting.id] = meeting
        tell(CoreEvent.Changed(meeting.copy(transcript = emptyList()), at = listed().indexOfFirst { it.id == meeting.id }))
    }
}

private fun Meeting.edited(edit: MeetingEdit): Meeting = when (edit) {
    is MeetingEdit.Rename -> copy(title = edit.title)
    is MeetingEdit.SetDone -> {
        val written = (minutes as MinutesState.Written).minutes
        val actions = written.actions.map { if (it.id == edit.action) it.copy(done = edit.done) else it }
        copy(minutes = MinutesState.Written(written.copy(actions = actions)))
    }
    else -> this
}

