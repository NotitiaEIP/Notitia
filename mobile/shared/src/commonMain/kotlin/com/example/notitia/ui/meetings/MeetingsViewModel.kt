package com.example.notitia.ui.meetings

import androidx.lifecycle.ViewModel
import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.ownedBy
import com.example.notitia.recording.MeetingRecorder
import com.example.notitia.recording.RecorderState
import com.example.notitia.storage.MeetingRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

// What the screens ask of the meetings. The recording itself lives in MeetingRecorder, beyond any screen.
class MeetingsViewModel(
    private val repository: MeetingRepository,
    private val recorder: MeetingRecorder,
) : ViewModel() {
    val meetings: StateFlow<List<Meeting>?> = repository.meetings
    val recording: StateFlow<RecorderState> = recorder.state

    fun progress(id: String): MeetingStatus? = recorder.progress(id)

    fun setDone(id: ActionId, done: Boolean) = repository.updateAction(id) { it.copy(done = done) }

    fun setOwner(id: ActionId, owner: String?) = repository.updateAction(id) { it.ownedBy(owner) }

    // Without a date, a reminder has no day: it does not come back on its own with the next date.
    fun setDue(id: ActionId, due: LocalDate?) = repository.updateAction(id) { it.copy(due = due, reminder = it.reminder && due != null) }

    fun setReminder(id: ActionId, on: Boolean) = repository.updateAction(id) { it.copy(reminder = on) }

    fun startRecording() = recorder.start()

    fun togglePause() = recorder.togglePause()

    fun stopRecording() = recorder.stop()

    fun dismissFailure() = recorder.dismissFailure()
}
