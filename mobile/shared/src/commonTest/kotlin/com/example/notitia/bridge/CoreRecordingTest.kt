package com.example.notitia.bridge

import androidx.compose.runtime.snapshots.Snapshot
import com.example.notitia.ai.MinutesWriter
import com.example.notitia.ai.SpeechEngine
import com.example.notitia.ai.audio
import com.example.notitia.bridge.CoreEvent.Recorder.Phase
import com.example.notitia.meeting.MinutesDraft
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.RecorderState
import com.example.notitia.meeting.RecordingProblem
import com.example.notitia.meeting.TranscriptLine
import com.example.notitia.recording.FakeMicrophone
import com.example.notitia.recording.Microphone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class CoreRecordingTest {
    private val dispatcher = ManualDispatcher()
    private val core = FakeCore()
    private val microphone = FakeMicrophone()
    private val session = CoreSession(core, directory = "meetings", key = { ByteArray(32) }, calls = dispatcher)
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-11-19T09:00:00Z")
    }

    private class Speech(private val said: String) : SpeechEngine {
        override suspend fun transcribe(wav: ByteArray, previousText: String) = said
    }

    private object Writer : MinutesWriter {
        val minutes = MinutesDraft.Written("Point sur la démo.", decisions = emptyList(), actions = emptyList())

        override suspend fun write(lines: List<TranscriptLine>, day: LocalDate) = minutes
    }

    private fun recording(microphone: Microphone? = this.microphone, speech: SpeechEngine? = null, writer: MinutesWriter? = null) =
        CoreRecording(session, microphone, speech, writer, clock, { TimeZone.of("Europe/Paris") }, work = dispatcher, engines = dispatcher)
            .also { settled() }

    // What was asked, then what that asked in turn, until nothing is left. The snapshots of the transcription too,
    // which Compose sends in the app.
    private fun settled() {
        do Snapshot.sendApplyNotifications() while (dispatcher.runAll())
    }

    private fun transcribed() = core.applied.filterIsInstance<Change.Transcribed>()

    @Test
    fun aRecordingStartsWithOneTrackAtTheOffsetOfThePhone() {
        recording().start()
        settled()
        // Paris in November: an hour ahead of UTC.
        assertEquals(listOf<CoreRequest>(Command.Start(tracks = 1, offsetMinutes = 60)), core.applied)
    }

    @Test
    fun theMicrophoneListensOnlyWhileTheCoreRecords() {
        val recording = recording()
        assertFalse(microphone.listening)
        recording.start()
        settled()
        assertTrue(microphone.listening && !microphone.paused)
        recording.togglePause()
        settled()
        assertTrue(microphone.paused)
        recording.togglePause()
        settled()
        // The same session: a pause stops the microphone, it does not let it go.
        assertTrue(microphone.listening && !microphone.paused)
        assertEquals(1, microphone.listens)
        recording.stop()
        settled()
        assertEquals(RecorderState.Idle, recording.state.value)
        assertFalse(microphone.listening)
    }

    @Test
    fun theSoundGoesToTheCoreWhichMakesTheMeetingAtTheFirstSound() {
        val recording = recording()
        recording.start()
        settled()
        assertNull(assertIs<RecorderState.Recording>(recording.state.value).meetingId)
        microphone.hear(audio(1_000))
        settled()
        assertEquals(RecorderState.Recording(fakeStart, 1.seconds, level = 0f, meetingId = FAKE_RECORDED), recording.state.value)
    }

    @Test
    fun aMicrophoneTakenAwayIsCutThenAResumeListensAgain() {
        val recording = recording()
        recording.start()
        settled()
        microphone.hear(audio(1_000))
        microphone.cutOff(RecordingProblem.Busy)
        settled()
        assertEquals(RecorderState.Interrupted(fakeStart, 1.seconds, FAKE_RECORDED), recording.state.value)
        recording.togglePause()
        settled()
        microphone.hear(audio(1_000))
        settled()
        assertEquals(2, microphone.listens)
        assertEquals(2.seconds, assertIs<RecorderState.Recording>(recording.state.value).recorded)
    }

    @Test
    fun theStepsToldAfterTheMicrophoneDiedOpenNoOther() {
        val recording = recording()
        recording.start()
        settled()
        microphone.hear(audio(1_000))
        settled()
        microphone.cutOff(RecordingProblem.Busy)
        // Sound queued before the microphone died is still told before the core reads the cut.
        core.tell(CoreEvent.Recorder(Phase.Recording, meeting = FAKE_RECORDED, startedAt = fakeStart, recorded = 1_100.milliseconds))
        settled()
        assertEquals(1, microphone.listens)
        assertIs<RecorderState.Interrupted>(recording.state.value)
    }

    // Stopped, then begun again, by the core before this app followed either: the microphone still open is the first
    // take's, and hears nothing of the next.
    @Test
    fun theMicrophoneOfATakeIsNeverHeardInTheNext() {
        val recording = recording()
        recording.start()
        settled()
        core.tell(CoreEvent.Recorder(Phase.Idle))
        core.tell(CoreEvent.Recorder(Phase.Recording, startedAt = fakeStart + 1.seconds))
        assertTrue(microphone.paused)
        settled()
        assertEquals(2, microphone.listens)
        assertFalse(microphone.paused)
    }

    @Test
    fun aRefusedMicrophoneFailsAndLeavesNothing() {
        val recording = recording(FakeMicrophone(refusal = RecordingProblem.Denied))
        recording.start()
        settled()
        assertEquals(RecorderState.Failed(RecordingProblem.Denied), recording.state.value)
        assertEquals(listOf<CoreRequest>(Command.Start(1, 60), Command.Cut(RecordingProblem.Denied)), core.applied)
        recording.dismissFailure()
        settled()
        assertEquals(RecorderState.Idle, recording.state.value)
        assertEquals(emptyList(), session.meetings.value)
    }

    @Test
    fun withoutAMicrophoneTheRecordingIsUnavailable() {
        val recording = recording(microphone = null)
        recording.start()
        settled()
        assertEquals(RecorderState.Failed(RecordingProblem.Unavailable), recording.state.value)
    }

    @Test
    fun theLinesGoToTheMeetingOfTheCoreAndTheMinutesOnceWritten() {
        val recording = recording(speech = Speech("Bonjour à tous."), writer = Writer)
        recording.start()
        settled()
        microphone.hear(audio(3_000) + audio(800, level = 0))
        settled()
        val said = listOf(TranscriptLine(Duration.ZERO, "Bonjour à tous."))
        assertEquals(listOf(Change.Transcribed(FAKE_RECORDED, said, MinutesDraft.Writing)), transcribed())
        assertNotNull(recording.progress(FAKE_RECORDED))
        recording.stop()
        settled()
        assertEquals(Change.Transcribed(FAKE_RECORDED, said, Writer.minutes), transcribed().last())
        assertNull(recording.progress(FAKE_RECORDED))
        assertIs<MinutesState.Written>(session.meetings.value?.single()?.minutes)
    }

    @Test
    fun aMeetingWithoutAnEngineDoesNotStayWriting() {
        val recording = recording()
        recording.start()
        settled()
        microphone.hear(audio(1_000))
        recording.stop()
        settled()
        assertEquals(listOf(Change.Transcribed(FAKE_RECORDED, emptyList(), MinutesDraft.Unavailable)), transcribed())
        assertEquals(MinutesState.Unavailable, session.meetings.value?.single()?.minutes)
    }

    @Test
    fun aRecordingStoppedBeforeAnySoundHandsNothingOver() {
        val recording = recording()
        recording.start()
        settled()
        recording.stop()
        settled()
        assertEquals(listOf<CoreRequest>(Command.Start(1, 60), Command.Stop), core.applied)
        assertEquals(emptyList(), session.meetings.value)
    }

    @Test
    fun aSecondStartLeavesTheRecordingAlone() {
        val recording = recording()
        recording.start()
        settled()
        microphone.hear(audio(1_000))
        recording.start()
        settled()
        assertEquals(1, microphone.listens)
        assertEquals(1.seconds, assertIs<RecorderState.Recording>(recording.state.value).recorded)
    }
}
