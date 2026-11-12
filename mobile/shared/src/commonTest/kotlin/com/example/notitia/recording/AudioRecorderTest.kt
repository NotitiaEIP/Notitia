package com.example.notitia.recording

import com.example.notitia.ai.audio
import com.example.notitia.ui.search.runNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class AudioRecorderTest {

    private val now = Instant.fromEpochMilliseconds(1_000)

    // Unconfined runs the microphone at once, so each heard piece shows in the state before the next line.
    private fun recorder(microphone: Microphone?) = AudioRecorder(microphone, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun theDurationIsTheAudioHeardNotTheTimeSpent() {
        val microphone = FakeMicrophone()
        val recorder = recorder(microphone)
        assertTrue(recorder.start(now) {})
        assertEquals(RecorderState.Recording(now, 0.seconds, level = 0f), recorder.state.value)
        microphone.hear(audio(1_000))
        microphone.hear(audio(500))
        assertEquals(1_500.milliseconds, (recorder.state.value as RecorderState.Active).recorded)
        assertEquals(1_500.milliseconds, runNow { recorder.stop() })
        assertEquals(RecorderState.Idle, recorder.state.value)
    }

    @Test
    fun aPauseReleasesTheMicrophoneAndAddsNothing() {
        val microphone = FakeMicrophone()
        val recorder = recorder(microphone)
        recorder.start(now) {}
        microphone.hear(audio(1_000))
        recorder.togglePause()
        assertTrue(microphone.paused)
        microphone.hear(audio(1_000))
        assertEquals(RecorderState.Paused(now, 1.seconds), recorder.state.value)
        recorder.togglePause()
        microphone.hear(audio(1_000))
        assertEquals(2.seconds, runNow { recorder.stop() })
    }

    @Test
    fun theLevelFollowsTheLoudness() {
        val microphone = FakeMicrophone()
        val recorder = recorder(microphone)
        recorder.start(now) {}
        fun levelOf(pcm: ByteArray): Float {
            microphone.hear(pcm)
            return (recorder.state.value as RecorderState.Recording).level
        }
        assertEquals(0f, levelOf(audio(100, level = 0)))
        val quiet = levelOf(audio(100, level = 300))
        val loud = levelOf(audio(100, level = 10_000))
        assertTrue(quiet > 0f && loud > quiet && loud <= 1f, "quiet: $quiet, loud: $loud")
    }

    @Test
    fun eachPieceHeardIsHandedOn() {
        val microphone = FakeMicrophone()
        val handed = mutableListOf<ByteArray>()
        recorder(microphone).start(now) { handed += it }
        val piece = audio(100)
        microphone.hear(piece)
        assertEquals(listOf(piece), handed)
    }

    @Test
    fun aRefusedMicrophoneFailsWithoutATimer() {
        val recorder = recorder(FakeMicrophone(refusal = MicrophoneProblem.Denied))
        recorder.start(now) {}
        assertEquals(RecorderState.Failed(MicrophoneProblem.Denied), recorder.state.value)
        assertNull(runNow { recorder.stop() })
        recorder.dismissFailure()
        assertEquals(RecorderState.Idle, recorder.state.value)
    }

    @Test
    fun withoutAMicrophoneTheRecordingIsUnavailable() {
        val recorder = recorder(microphone = null)
        recorder.start(now) {}
        assertEquals(RecorderState.Failed(MicrophoneProblem.Unavailable), recorder.state.value)
    }

    @Test
    fun aMicrophoneTakenAwayKeepsWhatWasHeardAndCanResume() {
        val microphone = FakeMicrophone()
        val recorder = recorder(microphone)
        recorder.start(now) {}
        microphone.hear(audio(1_000))
        microphone.cutOff(MicrophoneProblem.Busy)
        assertEquals(RecorderState.Interrupted(now, 1.seconds), recorder.state.value)
        recorder.togglePause()
        assertEquals(2, microphone.listens)
        microphone.hear(audio(1_000))
        assertIs<RecorderState.Recording>(recorder.state.value)
        assertEquals(2.seconds, runNow { recorder.stop() })
    }

    @Test
    fun aSecondStartLeavesTheRecordingAlone() {
        val microphone = FakeMicrophone()
        val recorder = recorder(microphone)
        recorder.start(now) {}
        microphone.hear(audio(1_000))
        assertFalse(recorder.start(now) {})
        assertEquals(1, microphone.listens)
        assertEquals(1.seconds, (recorder.state.value as RecorderState.Active).recorded)
    }
}
