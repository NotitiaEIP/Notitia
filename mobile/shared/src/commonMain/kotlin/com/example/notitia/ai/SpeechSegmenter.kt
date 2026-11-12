package com.example.notitia.ai

import com.example.notitia.recording.BYTES_PER_MS
import com.example.notitia.recording.BYTES_PER_SAMPLE
import com.example.notitia.recording.SAMPLE_RATE
import com.example.notitia.recording.pcmDuration
import com.example.notitia.recording.rms
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val FRAME_MS = 30
private const val FRAME_BYTES = FRAME_MS * BYTES_PER_MS
private const val MIN_SEGMENT_MS = 2_500
private const val MAX_SEGMENT_MS = 15_000
private const val SILENCE_TO_CUT_MS = 700
private const val MIN_SPEECH_MS = 300
private const val MIN_SPEECH_RMS = 300.0
private const val NOISE_MARGIN = 2.5
private const val NOISE_SMOOTHING = 0.95
private const val WAV_HEADER_BYTES = 44

class SpeechSegment(val start: Duration, val pcm: ByteArray) {
    val length: Duration get() = pcmDuration(pcm.size)
}

// Whisper takes files, not a stream. Cutting on pauses keeps the words whole, and a piece without speech is dropped:
// on silence, Whisper makes up text.
class SpeechSegmenter {
    private var pending = ByteArray(0)
    private val segment = ByteArray(MAX_SEGMENT_MS * BYTES_PER_MS + FRAME_BYTES)
    private var segmentBytes = 0
    private var segmentStartMs = 0
    private var elapsedMs = 0
    private var speechMs = 0
    private var trailingSilenceMs = 0
    private var noiseFloor = 0.0

    fun add(pcm: ByteArray): List<SpeechSegment> = buildList {
        val data = pending + pcm
        var offset = 0
        while (offset + FRAME_BYTES <= data.size) {
            addFrame(data, offset)?.let { add(it) }
            offset += FRAME_BYTES
        }
        pending = data.copyOfRange(offset, data.size)
    }

    fun flush(): SpeechSegment? {
        pending.copyInto(segment, segmentBytes)
        segmentBytes += pending.size
        pending = ByteArray(0)
        return cut()
    }

    private fun addFrame(data: ByteArray, offset: Int): SpeechSegment? {
        val level = rms(data, offset, offset + FRAME_BYTES)
        val isSpeech = level >= max(MIN_SPEECH_RMS, noiseFloor * NOISE_MARGIN)
        // The background noise follows the room slowly, quiet or loud.
        if (!isSpeech) {
            noiseFloor = if (noiseFloor == 0.0) level else noiseFloor * NOISE_SMOOTHING + level * (1 - NOISE_SMOOTHING)
        }

        data.copyInto(segment, segmentBytes, offset, offset + FRAME_BYTES)
        segmentBytes += FRAME_BYTES
        elapsedMs += FRAME_MS
        if (isSpeech) {
            speechMs += FRAME_MS
            trailingSilenceMs = 0
        } else {
            trailingSilenceMs += FRAME_MS
        }

        val segmentMs = segmentBytes / BYTES_PER_MS
        val pause = segmentMs >= MIN_SEGMENT_MS && trailingSilenceMs >= SILENCE_TO_CUT_MS
        return if (pause || segmentMs >= MAX_SEGMENT_MS) cut() else null
    }

    private fun cut(): SpeechSegment? {
        val found = if (speechMs >= MIN_SPEECH_MS) {
            SpeechSegment(segmentStartMs.milliseconds, segment.copyOf(segmentBytes))
        } else {
            null
        }
        segmentStartMs = elapsedMs
        segmentBytes = 0
        speechMs = 0
        trailingSilenceMs = 0
        return found
    }
}

// In memory only, for the time of a request: no WAV is ever stored (product rule 5).
fun pcmToWav(pcm: ByteArray): ByteArray {
    val wav = ByteArray(WAV_HEADER_BYTES + pcm.size)
    fun text(at: Int, value: String) = value.forEachIndexed { index, char -> wav[at + index] = char.code.toByte() }
    fun number(at: Int, value: Int, bytes: Int) = repeat(bytes) { wav[at + it] = (value shr (8 * it)).toByte() }

    text(0, "RIFF")
    number(4, WAV_HEADER_BYTES - 8 + pcm.size, bytes = 4)
    text(8, "WAVEfmt ")
    number(16, 16, bytes = 4)
    number(20, 1, bytes = 2)
    number(22, 1, bytes = 2)
    number(24, SAMPLE_RATE, bytes = 4)
    number(28, SAMPLE_RATE * BYTES_PER_SAMPLE, bytes = 4)
    number(32, BYTES_PER_SAMPLE, bytes = 2)
    number(34, BYTES_PER_SAMPLE * 8, bytes = 2)
    text(36, "data")
    number(40, pcm.size, bytes = 4)
    pcm.copyInto(wav, WAV_HEADER_BYTES)
    return wav
}
