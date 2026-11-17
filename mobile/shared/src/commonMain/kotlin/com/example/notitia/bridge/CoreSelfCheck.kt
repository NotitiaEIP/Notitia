package com.example.notitia.bridge

import com.example.notitia.meeting.ActionDraft
import com.example.notitia.meeting.ActionSource
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesDraft
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.TranscriptLine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

private const val ID = "core-self-check"
private const val SAID = "Self-check"
private const val TITLE = "Checked"

// Made up, so the marker and the folder say nothing of a real meeting. An action with every field, so each kind of
// value crosses both ways: a date, a time in the audio, a text that may be absent.
private val heard = Change.Record(
    id = ID,
    startedAt = Instant.fromEpochMilliseconds(1_794_303_000_000),
    duration = 30.minutes,
    transcript = listOf(TranscriptLine(Duration.ZERO, SAID)),
    minutes = MinutesDraft.Written(
        summary = SAID,
        decisions = listOf(SAID),
        actions = listOf(ActionDraft(SAID, owner = SAID, due = LocalDate(2026, 11, 20), ActionSource(SAID, speaker = null, Duration.ZERO))),
    ),
)

// Proves the bridge where the app runs: a made-up meeting goes to the core and comes back, each step answered by
// its event. On the iPhone it is the only proof there is: the CI runs it on a simulator, since the Swift that holds
// the core is written on machines that cannot compile it.
class CoreSelfCheck(
    private val core: NotitiaCore,
    private val directory: Path = Path(SystemTemporaryDirectory, ID),
    private val files: FileSystem = SystemFileSystem,
) {
    // As they crossed: read after the call, since nothing may be thrown back into the core.
    private val events = mutableListOf<String>()

    // Fixed words, safe to log: the name of a step, never what a meeting holds.
    fun run(): String = firstFailure()?.let { "core bridge: failed at $it" } ?: "core bridge: ok"

    private fun firstFailure(): String? = try {
        clear()
        when {
            core.open(directory.toString()) { events += it } != null -> "open"
            told() != CoreEvent.Listed(emptyList()) -> "list"
            send(heard) != null -> "record"
            else -> afterRecording(told())
        }
    } catch (_: Exception) {
        "exception"
    } finally {
        clear()
    }

    private fun afterRecording(recorded: CoreEvent?): String? {
        val action = (recorded as? CoreEvent.Changed)?.meeting?.takeIf { it.id == ID }?.actions()?.singleOrNull()
        return when {
            action == null || action.id.isEmpty() || action.due != LocalDate(2026, 11, 20) -> "recorded"
            send(Change.Edit(ID, MeetingEdit.Rename(TITLE))) != null || changed()?.title != TITLE -> "rename"
            send(Change.Edit(ID, MeetingEdit.SetDone(action.id, done = true))) != null ||
                changed()?.actions()?.singleOrNull()?.done != true -> "tick"
            read()?.takeIf { it.title == TITLE }?.transcript?.singleOrNull()?.text != SAID -> "read"
            core.meeting("$ID-missing").failure != CoreFailure.NotFound -> "missing meeting"
            core.apply("{") != CoreFailure.Invalid -> "refused change"
            send(Change.Delete(setOf(ID))) != null || told() != CoreEvent.Removed(setOf(ID)) -> "delete"
            else -> null
        }
    }

    private fun send(change: Change): CoreFailure? = core.apply(CoreJson.encodeToString(Change.serializer(), change))

    // What the last call told, and nothing kept for the next.
    private fun told(): CoreEvent? = events.lastOrNull()?.let { CoreJson.decodeFromString<CoreEvent>(it) }.also { events.clear() }

    private fun changed(): Meeting? = (told() as? CoreEvent.Changed)?.meeting

    private fun read(): Meeting? = core.meeting(ID).text?.let { CoreJson.decodeFromString<Meeting>(it) }

    private fun Meeting.actions() = (minutes as? MinutesState.Written)?.minutes?.actions

    private fun clear() {
        if (!files.exists(directory)) return
        files.list(directory).forEach { files.delete(it, mustExist = false) }
        files.delete(directory, mustExist = false)
    }
}
