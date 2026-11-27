package com.example.notitia.bridge

import com.example.notitia.meeting.AudioKeeping
import com.example.notitia.meeting.KeepAudio
import kotlinx.coroutines.flow.StateFlow

// The choice of the user, sent to the core, which keeps and applies it. What it is now is the session's, as the core
// tells it.
internal class CoreAudioKeeping(private val session: CoreSession) : AudioKeeping {
    override val keepAudio: StateFlow<KeepAudio?> = session.keepAudio

    override fun keep(choice: KeepAudio) = session.send(Choice(keepAudio = choice))
}
