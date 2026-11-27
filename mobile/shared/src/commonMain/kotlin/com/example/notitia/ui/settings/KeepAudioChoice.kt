package com.example.notitia.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.example.notitia.meeting.AudioKeeping
import com.example.notitia.meeting.KeepAudio

// A shorter choice erases at once the audio past it (ADR 0016), so it waits for a yes; a longer one erases nothing.
// The check reads the core's choice, never this.
@Stable
class KeepAudioChoice(private val keeping: AudioKeeping) {
    var asked: AskedChoice? by mutableStateOf(null)
        private set

    fun choose(choice: KeepAudio, anchor: Rect?) {
        // A second sheet over the first would never show on iPhone, and the screen would be stuck.
        if (asked != null) return
        // Compared with what the core has said: a choice still on its way to it is never longer than that.
        val now = keeping.keepAudio.value ?: return
        if (choice < now) asked = AskedChoice(choice, anchor) else keeping.keep(choice)
    }

    fun confirm() {
        asked?.let { keeping.keep(it.choice) }
        asked = null
    }

    fun cancel() {
        asked = null
    }
}

// anchor: the row in window pixels, which the iPhone's sheet grows out of.
data class AskedChoice(val choice: KeepAudio, val anchor: Rect?)
