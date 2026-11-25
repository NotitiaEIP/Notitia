package com.example.notitia.bridge

import com.example.notitia.meeting.Abilities
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.RecorderState
import com.example.notitia.recording.FakeMicrophone
import com.example.notitia.recording.audio
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

// The whole chain on the machine that builds: the session, its facades and a made-up microphone in front of the real
// core, through the bridge of the phone, each on the threads it has in the app. What the fakes cannot prove: that the
// app and the core agree on a recording, from the first sound to the deleted files.
class RustRecordingTest {
    private val folder = createTempDirectory("rust-recording").toFile()

    @AfterTest
    fun clear() {
        folder.deleteRecursively()
    }

    private fun files() = folder.list().orEmpty().filter { it.endsWith(".notitia") || it.endsWith(".audio") }.sorted()

    @Test
    fun aMeetingIsRecordedKeptPlayedBackAndDeleted() = runBlocking {
        val core = RustCore()
        val session = CoreSession(core, folder.path, key = { ByteArray(32) { 7 } })
        val meetings = CoreMeetings(session)
        val microphone = FakeMicrophone()
        val recording = CoreRecording(session, microphone)
        withTimeout(20.seconds) {
            meetings.meetings.first { it != null }
            recording.start()
            while (!microphone.listening) delay(5)
            microphone.hear(audio(2_000, level = 8_000))
            val heard = recording.state.first { (it as? RecorderState.Recording)?.recorded == 2.seconds }
            val meeting = checkNotNull((heard as RecorderState.Recording).meetingId)
            assertEquals(listOf("$meeting.audio", "$meeting.notitia"), files())
            recording.stop()
            recording.state.first { it == RecorderState.Idle }
            // No engine lent: the meeting waits for a model, not heard yet, its minutes owed.
            val kept = meetings.meetings.first { listed -> listed.orEmpty().any { it.id == meeting && it.duration == 2.seconds } }
            val waiting = kept.orEmpty().single { it.id == meeting }
            assertEquals(Pair(Duration.ZERO, MinutesState.Writing), waiting.transcribed to waiting.minutes)
            assertEquals(Abilities.None, meetings.abilities.value)
            assertEquals(64_000, core.sound(meeting, fromMs = 0, lengthMs = 2_000).pcm?.size)
            meetings.delete(setOf(meeting))
            meetings.meetings.first { listed -> listed.orEmpty().none { it.id == meeting } }
            // Told before its files are removed: a tick shows before the disk is done.
            while (files().isNotEmpty()) delay(5)
        }
    }
}
