package com.example.notitia.meeting

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// What the screens ask of a recording, which the Rust core holds: its state is the core's, as the core tells it.
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

sealed interface RecorderState {
    data object Idle : RecorderState

    // Nothing was recorded: the attempt is over, and the screen says why.
    data class Failed(val problem: RecordingProblem) : RecorderState

    sealed interface Active : RecorderState {
        val startedAt: Instant

        // The audio kept, pauses left out: the duration the meeting will have.
        val recorded: Duration

        // Made by the core at the first sound, null before: a recording refused leaves no meeting. The list leaves
        // it to the mini bar until it stops.
        val meetingId: String?
    }

    // level is the loudness of the last 100 ms, from 0 (silence) to 1.
    data class Recording(
        override val startedAt: Instant,
        override val recorded: Duration,
        val level: Float,
        override val meetingId: String? = null,
    ) : Active

    data class Paused(
        override val startedAt: Instant,
        override val recorded: Duration,
        override val meetingId: String? = null,
    ) : Active

    // The microphone, the disk or the core stopped it: what was heard stays, and resuming goes on with the same audio.
    data class Interrupted(
        override val startedAt: Instant,
        override val recorded: Duration,
        override val meetingId: String? = null,
    ) : Active
}

// For previews, which have no core: nothing records.
object PreviewRecording : Recording {
    override val state: StateFlow<RecorderState> = MutableStateFlow(RecorderState.Idle)

    override fun start() = Unit

    override fun togglePause() = Unit

    override fun stop() = Unit

    override fun dismissFailure() = Unit

    override fun progress(meetingId: String): MeetingStatus? = null
}

// As the core names them. Storage: no key to seal the audio with, so none is written.
@Serializable
enum class RecordingProblem {
    @SerialName("denied")
    Denied,

    @SerialName("busy")
    Busy,

    @SerialName("unavailable")
    Unavailable,

    @SerialName("storage")
    Storage,
}
