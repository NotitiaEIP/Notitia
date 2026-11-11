package com.example.notitia.ui.meetings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.notitia.ai.SpeechEngine
import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.ownedBy
import com.example.notitia.reminder.NoReminderScheduler
import com.example.notitia.reminder.ReminderScheduler
import com.example.notitia.reminder.ReminderSync
import com.example.notitia.reminder.reminders
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.reminder_message
import org.jetbrains.compose.resources.getPluralString

// A view model, so a rotation neither stops the microphone nor loses a transcription. speech is null when this
// build cannot record: the kept meetings still show.
class MeetingsViewModel(
    private val repository: MeetingRepository,
    private val speech: SpeechEngine?,
    private val writer: MinutesWriter?,
    reminderScheduler: ReminderScheduler = NoReminderScheduler,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    val meetings: StateFlow<List<Meeting>?> = repository.meetings

    // The reminders follow the meetings: ticking, moving or removing an action moves its notification too. A new line
    // of a transcription changes no reminder, so it schedules nothing.
    init {
        val sync = ReminderSync(reminderScheduler) { count -> getPluralString(Res.plurals.reminder_message, count, count) }
        viewModelScope.launch {
            meetings.filterNotNull()
                .map { meetings ->
                    val zone = TimeZone.currentSystemDefault()
                    reminders(meetings, zone, clock.now().toLocalDateTime(zone).date)
                }
                .distinctUntilChanged()
                .collect { sync.sync(it, clock.now()) }
        }
    }

    // The meetings still being transcribed, whose progress the kept meeting does not hold.
    private var transcribing by mutableStateOf(emptyMap<String, MeetingTranscription>())

    private var current: MeetingTranscription? = null
    private var microphone: Job? = null

    @Volatile
    private var paused = false

    fun progress(id: String): MeetingStatus? = transcribing[id]?.status

    fun setDone(id: ActionId, done: Boolean) = repository.updateAction(id) { it.copy(done = done) }

    fun setOwner(id: ActionId, owner: String?) = repository.updateAction(id) { it.ownedBy(owner) }

    // Without a date, a reminder has no day: it does not come back on its own with the next date.
    fun setDue(id: ActionId, due: LocalDate?) = repository.updateAction(id) { it.copy(due = due, reminder = it.reminder && due != null) }

    fun setReminder(id: ActionId, on: Boolean) = repository.updateAction(id) { it.copy(reminder = on) }

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
