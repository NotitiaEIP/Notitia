package com.example.notitia.ui.recording

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.notitia.ai.SpeechEngine
import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.clockTime
import com.example.notitia.ui.meetings.shortDateLabel
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.recording_title
import org.jetbrains.compose.resources.stringResource

// A view model, so a rotation neither stops the microphone nor loses a transcription.
class RecordedMeetings(private val speech: SpeechEngine) : ViewModel() {
    var meetings by mutableStateOf(emptyList<MeetingTranscription>())
        private set

    private var current: MeetingTranscription? = null
    private var microphone: Job? = null

    @Volatile
    private var paused = false

    fun start(startedAt: Instant) {
        if (!speech.canListen()) return
        val transcription = MeetingTranscription(startedAt, speech)
        current = transcription
        paused = false
        microphone = viewModelScope.launch { speech.listen({ paused }, transcription::addAudio) }
        viewModelScope.launch { transcription.transcribeAll() }
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    fun stop(duration: Duration) {
        val transcription = current ?: return
        current = null
        transcription.duration = duration
        meetings = listOf(transcription) + meetings
        val listening = microphone
        viewModelScope.launch {
            listening?.cancelAndJoin()
            transcription.finishListening()
        }
    }
}

@Composable
fun MeetingTranscription.listItem(): MeetingListItem {
    val start = startedAt.toLocalDateTime(TimeZone.currentSystemDefault())
    return MeetingListItem(
        id = id,
        title = stringResource(Res.string.recording_title, shortDateLabel(start.date), clockTime(start.time)),
        startedAt = startedAt,
        duration = duration,
        // Unknown: the speakers are not told apart yet (E2).
        participants = 1,
        status = status,
    )
}
