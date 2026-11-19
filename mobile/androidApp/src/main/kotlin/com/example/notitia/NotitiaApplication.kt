package com.example.notitia

import android.app.Application
import com.example.notitia.bridge.MeetingsKey
import com.example.notitia.bridge.RustCore
import com.example.notitia.bridge.meetingsDirectory
import com.example.notitia.recording.AndroidMicrophone
import com.example.notitia.reminder.AlarmReminderScheduler

class NotitiaApplication : Application() {
    // Created with the process rather than with an activity, which Back or a rotation can end.
    val notitia: Notitia by lazy {
        Notitia(
            core = RustCore(),
            meetingsDirectory = meetingsDirectory(this),
            meetingsKey = MeetingsKey(this)::read,
            microphone = AndroidMicrophone(this),
            speechEngine = speechEngineForBuild(),
            minutesWriter = minutesWriterForBuild(),
            answerEngine = answerEngineForBuild(),
            reminderScheduler = AlarmReminderScheduler(this),
        )
    }
}
