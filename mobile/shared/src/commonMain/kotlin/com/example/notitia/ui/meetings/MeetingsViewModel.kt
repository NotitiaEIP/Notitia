package com.example.notitia.ui.meetings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.notitia.meeting.Abilities
import com.example.notitia.meeting.ActionBoard
import com.example.notitia.meeting.ActionId
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.Meetings
import com.example.notitia.meeting.RecorderState
import com.example.notitia.meeting.Recording
import kotlin.time.Clock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

// What the screens ask of the meetings. The recording itself lives beyond any screen.
class MeetingsViewModel(
    private val kept: Meetings,
    private val recorder: Recording,
    clock: Clock = Clock.System,
) : ViewModel() {
    val meetings: StateFlow<List<Meeting>?> = kept.meetings
    val recording: StateFlow<RecorderState> = recorder.state
    val abilities: StateFlow<Abilities> = kept.abilities
    val board: StateFlow<ActionBoard?> = kept.board
    val lingering: StateFlow<Set<ActionId>> = kept.lingering

    // Seen as the meetings change, from the first list on, whichever screen shows them.
    val pace: StateFlow<TranscriptionPace> = kept.meetings
        .filterNotNull()
        .runningFold(TranscriptionPace()) { pace, meetings -> pace.next(meetings, clock.now()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TranscriptionPace())

    suspend fun transcript(id: String) = kept.transcript(id)

    fun showBoard(today: LocalDate) = kept.showBoard(today)

    fun setDone(id: ActionId, done: Boolean) = kept.setDone(id, done)

    fun tick(id: ActionId, done: Boolean) = kept.tick(id, done)

    fun letGo(ids: Set<ActionId>) = kept.letGo(ids)

    fun setOwner(id: ActionId, owner: String?) = kept.setOwner(id, owner)

    fun setDue(id: ActionId, due: LocalDate?) = kept.setDue(id, due)

    fun setReminder(id: ActionId, on: Boolean) = kept.setReminder(id, on)

    fun rename(id: String, title: String) = kept.rename(id, title)

    fun delete(ids: Set<String>) = kept.delete(ids)

    fun rewrite(id: String) = kept.rewrite(id)

    fun startRecording() = recorder.start()

    fun togglePause() = recorder.togglePause()

    fun stopRecording() = recorder.stop()

    fun dismissFailure() = recorder.dismissFailure()
}
