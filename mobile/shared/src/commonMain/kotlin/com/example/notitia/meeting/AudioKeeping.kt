package com.example.notitia.meeting

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// How long the audio of a meeting stays once it is transcribed, as the core names the choices, the shortest first. A
// meeting not transcribed yet keeps its audio a month whatever the choice: the core's rule, never this app's.
@Serializable
enum class KeepAudio {
    @SerialName("untilHeard")
    UntilHeard,

    @SerialName("day")
    Day,

    @SerialName("week")
    Week,

    @SerialName("month")
    Month,
}

// What the screens know of the choice, which the Rust core keeps for every app.
interface AudioKeeping {
    // null until the core has said: no default is shown in its place.
    val keepAudio: StateFlow<KeepAudio?>

    // A shorter choice erases at once the audio that passes it, but that of the meeting being recorded.
    fun keep(choice: KeepAudio)
}

// For previews, which have no core: the choice is held in memory.
class PreviewAudioKeeping(chosen: KeepAudio = KeepAudio.Month) : AudioKeeping {
    private val held = MutableStateFlow<KeepAudio?>(chosen)

    override val keepAudio: StateFlow<KeepAudio?> = held.asStateFlow()

    override fun keep(choice: KeepAudio) {
        held.value = choice
    }
}
