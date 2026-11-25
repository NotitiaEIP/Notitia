package com.example.notitia.ui.minutes

import com.example.notitia.meeting.Abilities
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.TranscriptLine
import com.example.notitia.meeting.WrittenMinutes
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.minutes_failed
import notitia.shared.generated.resources.minutes_rewrite
import notitia.shared.generated.resources.minutes_transcribing
import notitia.shared.generated.resources.minutes_unavailable
import notitia.shared.generated.resources.minutes_unheard
import notitia.shared.generated.resources.minutes_waiting
import notitia.shared.generated.resources.minutes_waiting_transcription
import notitia.shared.generated.resources.minutes_write
import notitia.shared.generated.resources.minutes_writing
import org.jetbrains.compose.resources.StringResource

// What the minutes screen says in place of minutes there is nothing to read of, and the button that asks for them
// again, when there is one.
enum class MinutesNotice(val text: StringResource, val rewrite: StringResource? = null) {
    Hearing(Res.string.minutes_transcribing),
    WaitingToHear(Res.string.minutes_waiting_transcription),
    Writing(Res.string.minutes_writing),
    WaitingToWrite(Res.string.minutes_waiting),
    Failed(Res.string.minutes_failed, Res.string.minutes_rewrite),
    NeverWritten(Res.string.minutes_unavailable, Res.string.minutes_write),
    NothingHeard(Res.string.minutes_unheard),
}

// From what the core holds of the meeting and what the engines of this device can do now. everyPassageFailed is read
// in the lines, only for minutes written empty: an engine that failed on each passage is not a meeting where nothing
// was said.
fun Meeting.minutesNotice(abilities: Abilities, everyPassageFailed: Boolean): MinutesNotice? {
    if (transcribed != null) return if (abilities.hear) MinutesNotice.Hearing else MinutesNotice.WaitingToHear
    return when (val state = minutes) {
        MinutesState.Writing -> if (abilities.write) MinutesNotice.Writing else MinutesNotice.WaitingToWrite
        MinutesState.Failed -> MinutesNotice.Failed
        MinutesState.Unavailable -> MinutesNotice.NeverWritten
        is MinutesState.Written -> MinutesNotice.NothingHeard.takeIf { everyPassageFailed && state.minutes.isEmpty() }
    }
}

fun WrittenMinutes.isEmpty(): Boolean = summary.isBlank() && decisions.isEmpty() && actions.isEmpty()

fun everyPassageFailed(lines: List<TranscriptLine>): Boolean = lines.isNotEmpty() && lines.all { it.text.isNullOrBlank() }
