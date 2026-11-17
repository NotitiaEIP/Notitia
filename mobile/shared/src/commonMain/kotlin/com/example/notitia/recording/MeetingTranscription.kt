package com.example.notitia.recording

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.notitia.ai.MinutesWriter
import com.example.notitia.ai.SpeechEngine
import com.example.notitia.ai.SpeechSegment
import com.example.notitia.ai.SpeechSegmenter
import com.example.notitia.ai.pcmToWav
import com.example.notitia.meeting.MeetingStatus
import com.example.notitia.meeting.MinutesDraft
import com.example.notitia.meeting.TranscriptLine
import com.example.notitia.meeting.recordedMeetingId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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

internal fun cleanTranscript(text: String): String {
    val cleaned = text.trim()
    return if (madeUpSentences.any { it.containsMatchIn(cleaned) }) "" else cleaned
}

// Lives while the meeting is heard and transcribed: MeetingRecorder hands it over at each change. Without speech, this
// build cannot transcribe: the meeting keeps its duration and no text.
@Stable
class MeetingTranscription(
    val startedAt: Instant,
    private val speech: SpeechEngine?,
    private val writer: MinutesWriter? = null,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val id = recordedMeetingId(startedAt)

    var duration by mutableStateOf(Duration.ZERO)
    var lines by mutableStateOf(emptyList<TranscriptLine>())
        private set
    var minutes by mutableStateOf<MinutesDraft>(MinutesDraft.Writing)
        private set

    private val segmenter = SpeechSegmenter()
    private val queue = Channel<SpeechSegment>(Channel.UNLIMITED)
    private var heard by mutableStateOf(Duration.ZERO)
    private var transcribed by mutableStateOf(Duration.ZERO)
    private var finished by mutableStateOf(false)

    // null once the minutes are settled: the kept meeting then says where it stands.
    val progress: MeetingStatus?
        get() = when {
            !finished -> MeetingStatus.Transcribing(
                progress = if (heard == Duration.ZERO) 0f else (transcribed / heard).toFloat().coerceIn(0f, 1f),
                remaining = (heard - transcribed) / TRANSCRIPTION_SPEED,
            )
            minutes == MinutesDraft.Writing -> MeetingStatus.WritingMinutes
            else -> null
        }

    // Nothing is held for an engine that is not there.
    fun addAudio(pcm: ByteArray) {
        if (speech != null) segmenter.add(pcm).forEach(::enqueue)
    }

    fun finishListening() {
        segmenter.flush()?.let(::enqueue)
        queue.close()
    }

    // One piece at a time, so the text stays in the order it was said. Without an engine it still waits for the end
    // of the listening, so the minutes are not settled while the meeting goes on.
    suspend fun transcribeAll() {
        for (segment in queue) transcribe(segment)
        finished = true
        minutes = writeMinutes()
    }

    private suspend fun transcribe(segment: SpeechSegment) {
        val speech = speech ?: return
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

    private suspend fun writeMinutes(): MinutesDraft {
        if (speech == null) return MinutesDraft.Unavailable
        val writer = writer ?: return MinutesDraft.Unavailable
        if (lines.none { it.text != null }) return MinutesDraft.Written("", emptyList(), emptyList())
        return try {
            writer.write(lines, startedAt.toLocalDateTime(timeZone).date)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            MinutesDraft.Failed
        }
    }

    private fun enqueue(segment: SpeechSegment) {
        heard += segment.length
        queue.trySend(segment)
    }

    private fun previousText(): String =
        lines.mapNotNull { it.text }.joinToString(" ").takeLast(PREVIOUS_TEXT_LENGTH)
}
