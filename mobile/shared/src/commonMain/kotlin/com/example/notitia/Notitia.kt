package com.example.notitia

import com.example.notitia.ai.SpeechEngine
import com.example.notitia.reminder.NoReminderScheduler
import com.example.notitia.reminder.ReminderScheduler
import com.example.notitia.reminder.ReminderSync
import com.example.notitia.reminder.reminders
import com.example.notitia.storage.MeetingRepository
import com.example.notitia.storage.MeetingStore
import com.example.notitia.storage.NoMeetingStore
import com.example.notitia.ui.meetings.sampleMeetings
import com.example.notitia.ui.minutes.MinutesWriter
import com.example.notitia.ui.search.AnswerEngine
import com.example.notitia.ui.search.SampleAnswerEngine
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.reminder_message
import org.jetbrains.compose.resources.getPluralString

// Built once per process, by the platform: screens come and go, the meetings and what works on them stay. speechEngine
// is null when this build cannot record: the kept meetings still show.
class Notitia(
    meetingStore: MeetingStore = NoMeetingStore,
    val speechEngine: SpeechEngine? = null,
    val minutesWriter: MinutesWriter? = null,
    val answerEngine: AnswerEngine = SampleAnswerEngine,
    reminderScheduler: ReminderScheduler = NoReminderScheduler,
    private val clock: Clock = Clock.System,
) {
    // Tied to no screen: closing the app's window must not stop what runs for the meetings.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Made-up meetings beside the user's, so the app has something to show before a first recording.
    val repository = MeetingRepository(meetingStore, sampleMeetings(clock.now()), clock)

    // The reminders follow the meetings: ticking, moving or removing an action moves its notification too. A new line
    // of a transcription changes no reminder, so it schedules nothing.
    init {
        val sync = ReminderSync(reminderScheduler) { count -> getPluralString(Res.plurals.reminder_message, count, count) }
        scope.launch {
            repository.meetings.filterNotNull()
                .map { meetings ->
                    val zone = TimeZone.currentSystemDefault()
                    reminders(meetings, zone, clock.now().toLocalDateTime(zone).date)
                }
                .distinctUntilChanged()
                .collect { sync.sync(it, clock.now()) }
        }
    }
}
