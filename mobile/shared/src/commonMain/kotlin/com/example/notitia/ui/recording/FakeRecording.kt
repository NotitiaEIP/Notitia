package com.example.notitia.ui.recording

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay

// A made-up recording until the recorder (E1) exists: it only counts seconds.
// It lives above the sheet, so closing the sheet does not stop it.
@Stable
class FakeRecording(val startedAt: Instant, elapsedSeconds: Long = 0, paused: Boolean = false) {
    var elapsedSeconds by mutableLongStateOf(elapsedSeconds)
        private set
    var paused by mutableStateOf(paused)
        private set

    val elapsed: Duration get() = elapsedSeconds.seconds

    fun togglePause() {
        paused = !paused
    }

    suspend fun run() {
        while (true) {
            delay(1.seconds)
            if (!paused) elapsedSeconds++
        }
    }

    companion object {
        val Saver: Saver<FakeRecording?, List<Long>> = Saver(
            save = { it?.let { listOf(it.startedAt.toEpochMilliseconds(), it.elapsedSeconds, if (it.paused) 1L else 0L) } },
            restore = { FakeRecording(Instant.fromEpochMilliseconds(it[0]), it[1], it[2] == 1L) },
        )
    }
}
