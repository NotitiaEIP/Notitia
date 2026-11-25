package com.example.notitia.bridge

import com.example.notitia.bridge.CoreEvent.Recorder.Phase
import com.example.notitia.meeting.RecorderState
import com.example.notitia.meeting.RecordingProblem
import com.example.notitia.recording.FakeMicrophone
import com.example.notitia.recording.Microphone
import com.example.notitia.recording.audio
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

class CoreRecordingTest {
    private val dispatcher = ManualDispatcher()
    private val core = FakeCore()
    private val microphone = FakeMicrophone()
    private val session = CoreSession(core, directory = "meetings", key = { ByteArray(32) }, calls = dispatcher)
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-11-19T09:00:00Z")
    }

    private fun recording(microphone: Microphone? = this.microphone) =
        CoreRecording(session, microphone, clock, { TimeZone.of("Europe/Paris") }, work = dispatcher).also { settled() }

    // What was asked, then what that asked in turn, until nothing is left.
    private fun settled() {
        dispatcher.runAll()
    }

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
    fun theSoundGoesToTheCoreAloneWhichMakesTheMeetingAtTheFirstSound() {
        val recording = recording()
        recording.start()
        settled()
        assertNull(assertIs<RecorderState.Recording>(recording.state.value).meetingId)
        val said = audio(1_000)
        microphone.hear(said)
        settled()
        assertEquals(RecorderState.Recording(fakeStart, 1.seconds, level = 0f, meetingId = FAKE_RECORDED), recording.state.value)
        assertContentEquals(said, core.sound(FAKE_RECORDED, fromMs = 0, lengthMs = 1_000).pcm)
        // Nothing else is asked of the core: it hears the meeting itself.
        assertEquals(listOf<CoreRequest>(Command.Start(1, 60)), core.applied)
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
