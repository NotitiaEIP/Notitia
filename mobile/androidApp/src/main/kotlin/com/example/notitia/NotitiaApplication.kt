package com.example.notitia

import android.app.Application
import com.example.notitia.bridge.RustCore
import com.example.notitia.recording.AndroidMicrophone
import com.example.notitia.reminder.AlarmReminderScheduler
import com.example.notitia.storage.phoneMeetingStore

class NotitiaApplication : Application() {
    private val core = RustCore()

    // Created with the process rather than with an activity, which Back or a rotation can end.
    val notitia: Notitia by lazy {
        Notitia(
            core = core,
            meetingStore = phoneMeetingStore(this),
            microphone = AndroidMicrophone(this),
            speechEngine = speechEngineForBuild(),
            minutesWriter = minutesWriterForBuild(),
            answerEngine = answerEngineForBuild(),
            reminderScheduler = AlarmReminderScheduler(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        checkCoreForBuild(core)
    }
}
