package com.example.notitia.meeting

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.StateFlow

// What the screens ask of a recording. The Kotlin recorder holds it today, the Rust core will once it records (T-58).
interface Recording {
    val state: StateFlow<RecorderState>

    fun start()

    fun togglePause()

    fun stop()

    fun dismissFailure()

    // null unless the meeting is still transcribed: the kept meeting does not hold that progress. Read during
    // composition, which follows it as it moves.
    fun progress(meetingId: String): MeetingStatus?
}

fun recordedMeetingId(startedAt: Instant) = "recorded-${startedAt.toEpochMilliseconds()}"

sealed interface RecorderState {
    data object Idle : RecorderState

    // Nothing was recorded: the attempt is over, and the screen says why.
    data class Failed(val problem: MicrophoneProblem) : RecorderState

    sealed interface Active : RecorderState {
        val startedAt: Instant

        // The audio actually heard, pauses left out: what the meeting keeps as its duration.
        val recorded: Duration

        // The meeting this recording becomes: the list leaves it to the mini bar until it stops.
        val meetingId: String get() = recordedMeetingId(startedAt)
    }

    // level is the loudness of the last read, from 0 (silence) to 1.
    data class Recording(override val startedAt: Instant, override val recorded: Duration, val level: Float) : Active

    data class Paused(override val startedAt: Instant, override val recorded: Duration) : Active

    // The microphone stopped on its own (a call, another app): what was heard stays, and resuming listens again.
    data class Interrupted(override val startedAt: Instant, override val recorded: Duration) : Active
}

enum class MicrophoneProblem { Denied, Busy, Unavailable }
