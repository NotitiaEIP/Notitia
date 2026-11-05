package com.example.notitia.ui.recording

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.notitia.ai.SpeechEngine
import com.example.notitia.ai.SpeechSegment
import com.example.notitia.ai.SpeechSegmenter
import com.example.notitia.ai.pcmToWav
import com.example.notitia.ui.meetings.MeetingStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.channels.Channel

// What the gateway reached in the study of 23/10, network included.
private const val TRANSCRIPTION_SPEED = 10
private const val PREVIOUS_TEXT_LENGTH = 200

// Sentences Whisper makes up on noise or silence, learned from video subtitles.
private val madeUpSentences = listOf(
    Regex("""amara\.org""", RegexOption.IGNORE_CASE),
    Regex("""sous-titr(es|age)""", RegexOption.IGNORE_CASE),
    Regex("""^merci d['’]avoir regardé""", RegexOption.IGNORE_CASE),
    Regex("""^abonnez-vous""", RegexOption.IGNORE_CASE),
    Regex("""^thanks? for watching""", RegexOption.IGNORE_CASE),
)

// text is null for a passage the engine could not transcribe.
data class TranscriptLine(val at: Duration, val text: String?)

internal fun cleanTranscript(text: String): String {
    val cleaned = text.trim()
    return if (madeUpSentences.any { it.containsMatchIn(cleaned) }) "" else cleaned
}

// Kept in memory until US-16 stores the meetings: closing the app forgets it.
@Stable
class MeetingTranscription(val startedAt: Instant, private val speech: SpeechEngine) {
    val id = "recorded-${startedAt.toEpochMilliseconds()}"

    var duration by mutableStateOf(Duration.ZERO)
    var lines by mutableStateOf(emptyList<TranscriptLine>())
        private set

    private val segmenter = SpeechSegmenter()
    private val queue = Channel<SpeechSegment>(Channel.UNLIMITED)
    private var heard by mutableStateOf(Duration.ZERO)
    private var transcribed by mutableStateOf(Duration.ZERO)
    private var finished by mutableStateOf(false)

    val status: MeetingStatus
        get() = if (finished) {
            MeetingStatus.Ready(actions = 0)
        } else {
            MeetingStatus.Transcribing(
                progress = if (heard == Duration.ZERO) 0f else (transcribed / heard).toFloat().coerceIn(0f, 1f),
                remaining = (heard - transcribed) / TRANSCRIPTION_SPEED,
            )
        }

    fun addAudio(pcm: ByteArray) = segmenter.add(pcm).forEach(::enqueue)

    fun finishListening() {
        segmenter.flush()?.let(::enqueue)
        queue.close()
    }

    // One piece at a time, so the text stays in the order it was said.
    suspend fun transcribeAll() {
        for (segment in queue) {
            val line = try {
                cleanTranscript(speech.transcribe(pcmToWav(segment.pcm), previousText()))
                    .takeIf { it.isNotEmpty() }
                    ?.let { TranscriptLine(segment.start, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TranscriptLine(segment.start, text = null)
            }
            line?.let { lines = lines + it }
            transcribed += segment.length
        }
        finished = true
    }

    private fun enqueue(segment: SpeechSegment) {
        heard += segment.length
        queue.trySend(segment)
    }

    private fun previousText(): String =
        lines.mapNotNull { it.text }.joinToString(" ").takeLast(PREVIOUS_TEXT_LENGTH)
}
