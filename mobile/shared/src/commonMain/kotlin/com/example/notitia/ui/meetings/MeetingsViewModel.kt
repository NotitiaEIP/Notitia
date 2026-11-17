package com.example.notitia.ui.meetings

import androidx.lifecycle.ViewModel
import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MeetingStatus
import com.example.notitia.meeting.Meetings
import com.example.notitia.meeting.RecorderState
import com.example.notitia.meeting.Recording
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

// What the screens ask of the meetings. The recording itself lives beyond any screen.
class MeetingsViewModel(
    private val kept: Meetings,
    private val recorder: Recording,
) : ViewModel() {
    val meetings: StateFlow<List<Meeting>?> = kept.meetings
    val recording: StateFlow<RecorderState> = recorder.state

    fun progress(id: String): MeetingStatus? = recorder.progress(id)

    suspend fun transcript(id: String) = kept.transcript(id)

    fun setDone(id: ActionId, done: Boolean) = kept.setDone(id, done)

    fun setOwner(id: ActionId, owner: String?) = kept.setOwner(id, owner)

    fun setDue(id: ActionId, due: LocalDate?) = kept.setDue(id, due)

    fun setReminder(id: ActionId, on: Boolean) = kept.setReminder(id, on)

    fun rename(id: String, title: String) = kept.rename(id, title)

    fun delete(ids: Set<String>) = kept.delete(ids)

    fun startRecording() = recorder.start()

    fun togglePause() = recorder.togglePause()

    fun stopRecording() = recorder.stop()

    fun dismissFailure() = recorder.dismissFailure()
}
