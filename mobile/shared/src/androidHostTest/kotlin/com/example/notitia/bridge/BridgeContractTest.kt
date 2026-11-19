package com.example.notitia.bridge

import com.example.notitia.bridge.CoreEvent.Recorder.Phase
import com.example.notitia.meeting.ActionDraft
import com.example.notitia.meeting.ActionSource
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.MinutesDraft
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.RecordingProblem
import com.example.notitia.meeting.TranscriptLine
import com.example.notitia.meeting.WrittenMinutes
import com.example.notitia.meeting.meeting
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject

// The text of the bridge has one shape and two sides that write and read it, the Rust core and this app: both test
// themselves against the same examples, so neither can drift alone. On the host, because only there can a test read
// a file of the repo.
class BridgeContractTest {
    private val fixtures = File("../../core/tests/fixtures")
    private val examples = fixtures.resolve("bridge")

    @Test
    fun theChangesThisAppWritesAreTheOnesTheCoreReads() {
        fun edit(edit: MeetingEdit) = Change.Edit("recorded-1", edit)
        fun recorded(duration: Duration, transcript: List<TranscriptLine>, minutes: MinutesDraft) =
            Change.Record("recorded-9", Instant.parse("2026-11-11T07:00:00Z"), duration, transcript, minutes)
        val hello = TranscriptLine(Duration.ZERO, "Bonjour à tous.")
        val heardAndWritten = MinutesDraft.Written(
            summary = "Point sur la démo.",
            decisions = listOf("La démo a lieu le 15 novembre."),
            actions = listOf(
                ActionDraft(
                    "Préparer la démo", "Lucas", LocalDate(2026, 11, 14),
                    ActionSource("Je prépare la démo.", speaker = null, at = 12.minutes + 4.seconds),
                ),
                ActionDraft("Rédiger le registre RGPD", owner = null, due = null),
            ),
        )
        val changes = listOf(
            edit(MeetingEdit.Rename("Point produit")),
            edit(MeetingEdit.SetDone("0", done = true)),
            edit(MeetingEdit.SetOwner("0", "Nathan")),
            edit(MeetingEdit.SetOwner("0", owner = null)),
            edit(MeetingEdit.SetDue("0", LocalDate(2026, 11, 20))),
            edit(MeetingEdit.SetDue("0", due = null)),
            edit(MeetingEdit.SetReminder("0", on = true)),
            Change.Delete(setOf("recorded-1", "recorded-3")),
            recorded(1.minutes, emptyList(), MinutesDraft.Writing),
            recorded(90.seconds, listOf(hello, TranscriptLine(30.seconds, text = null)), heardAndWritten),
            Change.Lend(listOf(meeting("sample-1").copy(transcript = listOf(hello)))),
        )
        assertEquals(
            CoreJson.parseToJsonElement(examples.resolve("changes.json").readText()),
            CoreJson.encodeToJsonElement(ListSerializer(Change.serializer()), changes),
        )
    }

    @Test
    fun theEventsTheCoreWritesAreTheOnesThisAppReads() {
        val reviewed = meeting(
            "recorded-1",
            MinutesState.Written(
                WrittenMinutes(
                    summary = "Point sur la démo.",
                    decisions = listOf("La démo a lieu le 15 novembre."),
                    actions = listOf(
                        MinutesAction(
                            "0", "Préparer la démo", "Lucas", LocalDate(2026, 11, 14), done = true,
                            ActionSource("Je prépare la démo.", speaker = "Nathan", at = 12.minutes + 4.seconds),
                            reminder = true,
                        ),
                        MinutesAction("6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f", "Rédiger le registre RGPD", owner = null, due = null, done = false),
                    ),
                ),
            ),
        ).copy(
            modifiedAt = Instant.parse("2026-11-11T08:00:00Z"),
            device = "phone-2",
            title = "Revue de sprint",
            speakers = listOf("Lucas", "Nathan"),
        )
        val events = listOf(
            CoreEvent.Listed(
                listOf(
                    meeting("recorded-2", MinutesState.Writing),
                    meeting("recorded-3", MinutesState.Failed),
                    meeting("recorded-4", MinutesState.Unavailable),
                ),
            ),
            CoreEvent.Changed(reviewed, at = 1),
            CoreEvent.Removed(setOf("recorded-1", "recorded-3")),
            CoreEvent.Unsaved(setOf("recorded-1")),
            CoreEvent.Unsaved(emptySet()),
        )
        val read = CoreJson.decodeFromString(ListSerializer(CoreEvent.serializer()), examples.resolve("events.json").readText())
        assertEquals(events, read)
    }

    @Test
    fun theRecorderIsCommandedAndFollowedAsTheCoreReadsAndWritesIt() {
        val fixture = CoreJson.parseToJsonElement(examples.resolve("recorder.json").readText()).jsonObject
        val hello = TranscriptLine(Duration.ZERO, "Bonjour à tous.")
        val silence = TranscriptLine(30.seconds, text = null)
        val demo = ActionDraft(
            "Préparer la démo", "Lucas", LocalDate(2026, 11, 14),
            ActionSource("Je prépare la démo.", speaker = null, at = 12.minutes + 4.seconds),
        )
        val written = MinutesDraft.Written("Point sur la démo.", decisions = listOf("La démo a lieu le 15 novembre."), listOf(demo))
        val requests = listOf(
            Command.Start(tracks = 1, offsetMinutes = 60),
            Command.Start(tracks = 2, offsetMinutes = -210),
            Command.Pause,
            Command.Resume,
            Command.Stop,
            Command.Cut(RecordingProblem.Denied),
            Command.Cut(RecordingProblem.Busy),
            Command.Cut(RecordingProblem.Unavailable),
            Command.Dismiss,
            Change.Transcribed("6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f", listOf(hello, silence), MinutesDraft.Writing),
            Change.Transcribed(
                "6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f",
                listOf(hello, silence, TranscriptLine(12.minutes + 4.seconds, "Je prépare la démo.")),
                written,
            ),
        )
        assertEquals(fixture.getValue("requests"), CoreJson.encodeToJsonElement(ListSerializer(CoreRequest.serializer()), requests))
        val started = Instant.parse("2026-11-18T09:00:00Z")
        val heard = CoreEvent.Recorder(Phase.Recording, meeting = "6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f", startedAt = started, recorded = 61.seconds, level = 0.42f)
        val events = listOf(
            CoreEvent.Recorder(Phase.Idle),
            CoreEvent.Recorder(Phase.Failed, problem = RecordingProblem.Storage),
            CoreEvent.Recorder(Phase.Recording, startedAt = started),
            heard,
            heard.copy(state = Phase.Paused, level = 0f),
            heard.copy(state = Phase.Interrupted, level = 0f),
            CoreEvent.Removed(setOf("recorded-1")),
        )
        assertEquals(events, CoreJson.decodeFromJsonElement(ListSerializer(CoreEvent.serializer()), fixture.getValue("events")))
    }

    // The reference of the format (ADR 0003), which the core writes to the byte: a whole meeting crosses in that shape.
    @Test
    fun aWholeMeetingIsReadWithItsLines() {
        val file = CoreJson.parseToJsonElement(fixtures.resolve("meeting-v2.notitia").readText()).jsonObject
        val meeting = CoreJson.decodeFromJsonElement(Meeting.serializer(), file.getValue("meeting"))
        assertEquals(listOf(TranscriptLine(Duration.ZERO, "Bonjour à tous."), TranscriptLine(30.seconds, text = null)), meeting.transcript)
        assertEquals(Triple("recorded-1", 42.minutes, "Revue de sprint"), Triple(meeting.id, meeting.duration, meeting.title))
        val actions = (meeting.minutes as MinutesState.Written).minutes.actions
        assertEquals(listOf("0", "1"), actions.map { it.id })
        assertEquals(ActionSource("Je prépare la démo.", speaker = null, at = 12.minutes + 4.seconds), actions.first().source)
    }
}
