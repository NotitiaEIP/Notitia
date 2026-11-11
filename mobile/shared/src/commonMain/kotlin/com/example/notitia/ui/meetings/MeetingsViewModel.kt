package com.example.notitia.ui.meetings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.notitia.ai.SpeechEngine
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.storage.MeetingRepository
import com.example.notitia.ui.minutes.MinutesWriter
import com.example.notitia.ui.recording.MeetingTranscription
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

// A view model, so a rotation neither stops the microphone nor loses a transcription. speech is null when this
// build cannot record: the kept meetings still show.
class MeetingsViewModel(
    private val repository: MeetingRepository,
    private val speech: SpeechEngine?,
    private val writer: MinutesWriter?,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    val meetings: StateFlow<List<Meeting>?> = repository.meetings

    // The meetings still being transcribed, whose progress the kept meeting does not hold.
    private var transcribing by mutableStateOf(emptyMap<String, MeetingTranscription>())

    private var current: MeetingTranscription? = null
    private var microphone: Job? = null

    @Volatile
    private var paused = false

    fun progress(id: String): MeetingStatus? = transcribing[id]?.status

    fun start(startedAt: Instant) {
        val speech = speech?.takeIf { it.canListen() } ?: return
        val transcription = MeetingTranscription(startedAt, speech, writer)
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
        transcribing += transcription.id to transcription
        val listening = microphone
        viewModelScope.launch {
            listening?.cancelAndJoin()
            transcription.finishListening()
        }
        // Kept at each new line, so an app killed mid-transcription still has what was heard.
        viewModelScope.launch {
            snapshotFlow { transcription.lines to transcription.minutes }
                .onEach { repository.save(transcription.toMeeting(repository.device, clock.now())) }
                .first { (_, minutes) -> minutes != MinutesState.Writing }
            transcribing -= transcription.id
        }
    }
}
