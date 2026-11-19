package com.example.notitia.recording

import androidx.compose.runtime.snapshots.Snapshot
import com.example.notitia.ai.audio
import com.example.notitia.meeting.MeetingKeeper
import com.example.notitia.meeting.MinutesDraft
import com.example.notitia.meeting.RecorderState
import com.example.notitia.meeting.RecordingProblem
import com.example.notitia.meeting.recordedMeetingId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration
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

    // Where the recorder last stood, as the core receives it.
    private class Kept(val id: String, val duration: Duration, val minutes: MinutesDraft)

    private var kept: Kept? = null
    private val keeper = MeetingKeeper { id, _, duration, _, minutes -> kept = Kept(id, duration, minutes) }

    // Unconfined runs the microphone and the transcription at once, so each step shows right after it.
    private fun recorder(microphone: Microphone) =
        MeetingRecorder(microphone, speech = null, writer = null, keeper, CoroutineScope(Dispatchers.Unconfined), clock)

    @Test
    fun theMeetingIsKeptAsSoonAsTheMicrophoneIsHeard() {
        val recorder = recorder(microphone)
        recorder.start()
        assertNull(kept)
        microphone.hear(audio(1_000))
        assertEquals(recordedMeetingId(now), kept?.id)
        assertEquals(1.seconds, kept?.duration)
        assertEquals(MinutesDraft.Writing, kept?.minutes)
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
        assertEquals(1.5.seconds, kept?.duration)
        // This build has no engine: the meeting keeps its duration and no text.
        assertEquals(MinutesDraft.Unavailable, kept?.minutes)
        assertEquals(RecorderState.Idle, recorder.state.value)
    }

    @Test
    fun aRefusedMicrophoneLeavesNoMeeting() {
        val recorder = recorder(FakeMicrophone(refusal = RecordingProblem.Denied))
        recorder.start()
        assertEquals(RecorderState.Failed(RecordingProblem.Denied), recorder.state.value)
        assertNull(kept)
    }
}
