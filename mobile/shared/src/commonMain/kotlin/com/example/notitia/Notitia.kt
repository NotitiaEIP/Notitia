package com.example.notitia

import com.example.notitia.ai.MinutesWriter
import com.example.notitia.ai.SpeechEngine
import com.example.notitia.bridge.CoreMeetings
import com.example.notitia.bridge.NotitiaCore
import com.example.notitia.meeting.MeetingKeeper
import com.example.notitia.meeting.Meetings
import com.example.notitia.meeting.PreviewMeetings
import com.example.notitia.meeting.Recording
import com.example.notitia.recording.MeetingRecorder
import com.example.notitia.recording.Microphone
import com.example.notitia.reminder.NoReminderScheduler
import com.example.notitia.reminder.ReminderScheduler
import com.example.notitia.reminder.ReminderSync
import com.example.notitia.reminder.reminders
import com.example.notitia.ui.meetings.sampleMeetings
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

// Built once per process, by the platform: screens come and go, the meetings and what works on them stay. microphone
// is null on a platform that cannot record yet, speechEngine when this build cannot transcribe, core in a preview.
class Notitia(
    core: NotitiaCore? = null,
    // Chosen by the platform, which keeps it out of every backup (product rule 3).
    meetingsDirectory: String = "",
    microphone: Microphone? = null,
    speechEngine: SpeechEngine? = null,
    minutesWriter: MinutesWriter? = null,
    val answerEngine: AnswerEngine = SampleAnswerEngine,
    reminderScheduler: ReminderScheduler = NoReminderScheduler,
    private val clock: Clock = Clock.System,
) {
    // Tied to no screen: closing the app's window must not stop what runs for the meetings.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Made-up meetings beside the user's, so the app has something to show before a first recording.
    private val samples = sampleMeetings(clock.now())
    private val kept = core?.let { CoreMeetings(it, meetingsDirectory, samples) }

    // All the screens know of the meetings and of a recording: what holds them can change without touching a screen.
    val meetings: Meetings = kept ?: PreviewMeetings(samples)

    val recording: Recording =
        MeetingRecorder(microphone, speechEngine, minutesWriter, kept ?: MeetingKeeper { _, _, _, _, _ -> }, scope, clock)

    // The reminders follow the meetings: ticking, moving or removing an action moves its notification too. A new line
    // of a transcription changes no reminder, so it schedules nothing.
    init {
        val sync = ReminderSync(reminderScheduler) { count -> getPluralString(Res.plurals.reminder_message, count, count) }
        scope.launch {
            meetings.meetings.filterNotNull()
                .map { meetings ->
                    val zone = TimeZone.currentSystemDefault()
                    reminders(meetings, zone, clock.now().toLocalDateTime(zone).date)
                }
                .distinctUntilChanged()
                .collect { sync.sync(it, clock.now()) }
        }
    }
}
