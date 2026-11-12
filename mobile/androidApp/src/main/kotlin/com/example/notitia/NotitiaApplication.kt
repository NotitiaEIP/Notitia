package com.example.notitia

import android.app.Application
import com.example.notitia.reminder.AlarmReminderScheduler
import com.example.notitia.storage.phoneMeetingStore

class NotitiaApplication : Application() {
    // Created with the process rather than with an activity, which Back or a rotation can end.
    val notitia: Notitia by lazy {
        Notitia(
            meetingStore = phoneMeetingStore(this),
            speechEngine = speechEngineForBuild(this),
            minutesWriter = minutesWriterForBuild(),
            answerEngine = answerEngineForBuild(),
            reminderScheduler = AlarmReminderScheduler(this),
        )
    }
}
