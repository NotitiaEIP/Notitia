package com.example.notitia.ui.recording

import com.example.notitia.ai.SpeechEngine
import com.example.notitia.ai.audio
import com.example.notitia.ui.meetings.MeetingStatus
import com.example.notitia.ui.search.runNow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MeetingTranscriptionTest {

    private val startedAt = Instant.fromEpochMilliseconds(1_000)

    // Each reply answers one piece of speech; a failure is thrown instead of returned.
    private class FakeSpeech(vararg replies: Any) : SpeechEngine {
        private val remaining = replies.toMutableList()
        val previousTexts = mutableListOf<String>()
        val wavs = mutableListOf<ByteArray>()

        override fun canListen() = true

        override suspend fun listen(isPaused: () -> Boolean, onAudio: (ByteArray) -> Unit) = Unit

        override suspend fun transcribe(wav: ByteArray, previousText: String): String {
            wavs += wav
            previousTexts += previousText
            return when (val reply = remaining.removeAt(0)) {
                is Exception -> throw reply
                else -> reply as String
            }
        }
    }

    private fun transcribed(speech: FakeSpeech, pieces: Int): MeetingTranscription {
        val transcription = MeetingTranscription(startedAt, speech)
        repeat(pieces) { transcription.addAudio(audio(3_000) + audio(800, level = 0)) }
        transcription.finishListening()
        runNow { transcription.transcribeAll() }
        return transcription
    }

    @Test
    fun theLinesKeepTheOrderAndTimeOfTheSpeech() {
        val transcription = transcribed(FakeSpeech(" Bonjour à tous. ", "On commence."), pieces = 2)
        assertEquals(listOf("Bonjour à tous.", "On commence."), transcription.lines.map { it.text })
        assertEquals(Duration.ZERO, transcription.lines[0].at)
        assertTrue(transcription.lines[1].at in 3.seconds..4.seconds, "at: ${transcription.lines[1].at}")
    }

    @Test
    fun aFailedPieceIsMarkedAndTheNextOnesGoOn() {
        val transcription = transcribed(
            FakeSpeech("Bonjour.", IllegalStateException("HTTP 502"), "On continue."),
            pieces = 3,
        )
        assertEquals(listOf("Bonjour.", null, "On continue."), transcription.lines.map { it.text })
        assertEquals(MeetingStatus.Ready(actions = 0), transcription.status)
    }

    @Test
    fun aCancelledTranscriptionDoesNotCountAsFailedOrFinished() {
        val transcription = MeetingTranscription(startedAt, FakeSpeech(CancellationException("closed")))
        transcription.addAudio(audio(3_000) + audio(800, level = 0))
        assertFailsWith<CancellationException> { runNow { transcription.transcribeAll() } }
        assertTrue(transcription.lines.isEmpty())
        assertIs<MeetingStatus.Transcribing>(transcription.status)
    }

    @Test
    fun whatWhisperMakesUpOnSilenceIsDropped() {
        val transcription = transcribed(
            FakeSpeech("Sous-titrage ST' 501", "Merci d’avoir regardé cette vidéo", "   ", "Le budget tient."),
            pieces = 4,
        )
        assertEquals(listOf("Le budget tient."), transcription.lines.map { it.text })
    }

    @Test
    fun theEndOfTheTextGuidesTheNextPiece() {
        val long = "a".repeat(300)
        val speech = FakeSpeech(long, "Suite.")
        transcribed(speech, pieces = 2)
        assertEquals("", speech.previousTexts[0])
        assertEquals("a".repeat(200), speech.previousTexts[1])
    }

    @Test
    fun theEngineReceivesAWavOfThePiece() {
        val speech = FakeSpeech("Bonjour.")
        transcribed(speech, pieces = 1)
        assertEquals("RIFF", speech.wavs.single().decodeToString(0, 4))
    }

    @Test
    fun theStatusGoesFromTranscribingToReady() {
        val transcription = MeetingTranscription(startedAt, FakeSpeech("Bonjour."))
        transcription.addAudio(audio(3_000) + audio(800, level = 0))
        transcription.finishListening()
        val before = assertIs<MeetingStatus.Transcribing>(transcription.status)
        assertEquals(0f, before.progress)
        assertTrue(before.remaining > Duration.ZERO)

        runNow { transcription.transcribeAll() }
        assertEquals(MeetingStatus.Ready(actions = 0), transcription.status)
    }

    @Test
    fun aMeetingWithoutSpeechFinishesWithoutCallingTheEngine() {
        val speech = FakeSpeech()
        val transcription = MeetingTranscription(startedAt, speech)
        transcription.addAudio(audio(5_000, level = 0))
        transcription.finishListening()
        runNow { transcription.transcribeAll() }
        assertTrue(speech.wavs.isEmpty())
        assertEquals(MeetingStatus.Ready(actions = 0), transcription.status)
    }
}
