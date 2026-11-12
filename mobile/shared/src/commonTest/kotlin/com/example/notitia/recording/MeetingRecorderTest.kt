package com.example.notitia.recording

import androidx.compose.runtime.snapshots.Snapshot
import com.example.notitia.ai.audio
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.storage.MeetingRepository
import com.example.notitia.storage.NoMeetingStore
import com.example.notitia.ui.recording.recordedMeetingId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class MeetingRecorderTest {

    private val now = Instant.fromEpochMilliseconds(1_000)
    private val clock = object : Clock {
        override fun now() = this@MeetingRecorderTest.now
    }
    private val microphone = FakeMicrophone()

    // Unconfined runs the microphone, the transcription and each write at once, so each step shows right after it.
    private val repository = MeetingRepository(NoMeetingStore, clock = clock, disk = Dispatchers.Unconfined)

    private fun recorder(microphone: Microphone) =
        MeetingRecorder(microphone, speech = null, writer = null, repository, CoroutineScope(Dispatchers.Unconfined), clock)

    private fun kept(): Meeting? = repository.meetings.value?.firstOrNull { it.id == recordedMeetingId(now) }

    @Test
    fun theMeetingIsKeptAsSoonAsTheMicrophoneIsHeard() {
        val recorder = recorder(microphone)
        recorder.start()
        assertNull(kept())
        microphone.hear(audio(1_000))
        assertEquals(1.seconds, kept()?.duration)
        assertEquals(MinutesState.Writing, kept()?.minutes)
    }

    @Test
    fun theKeptDurationIsTheAudioHeard() {
        val recorder = recorder(microphone)
        recorder.start()
        microphone.hear(audio(1_000))
        recorder.togglePause()
        microphone.hear(audio(1_000))
        recorder.togglePause()
        microphone.hear(audio(500))
        recorder.stop()
        Snapshot.sendApplyNotifications()
        assertEquals(1.5.seconds, kept()?.duration)
        // This build has no engine: the meeting keeps its duration and no text.
        assertEquals(MinutesState.Unavailable, kept()?.minutes)
        assertEquals(RecorderState.Idle, recorder.state.value)
    }

    @Test
    fun aRefusedMicrophoneLeavesNoMeeting() {
        val recorder = recorder(FakeMicrophone(refusal = MicrophoneProblem.Denied))
        recorder.start()
        assertEquals(RecorderState.Failed(MicrophoneProblem.Denied), recorder.state.value)
        assertNull(kept())
    }
}
