package com.example.notitia.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.util.concurrent.Executor
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

// A tenth of a second per read: the wave moves smoothly and the timer stays exact.
private const val READ_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE / 10
private const val PAUSE_CHECK_MS = 100L

private val CALL_MODES = setOf(
    AudioManager.MODE_IN_CALL,
    AudioManager.MODE_IN_COMMUNICATION,
    AudioManager.MODE_CALL_SCREENING,
    AudioManager.MODE_CALL_REDIRECT,
    AudioManager.MODE_COMMUNICATION_REDIRECT,
)

class AndroidMicrophone(context: Context) : Microphone {
    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)

    // The permission is checked just before.
    @SuppressLint("MissingPermission")
    override suspend fun listen(isPaused: () -> Boolean, onAudio: (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw MicrophoneException(MicrophoneProblem.Denied)
        }
        // Since Android 10 a call does not refuse the microphone to other apps, it hands them silence: the timer would
        // run on an empty recording.
        if (audio.mode in CALL_MODES) throw MicrophoneException(MicrophoneProblem.Busy)
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val channel = AudioFormat.CHANNEL_IN_MONO
        val bufferBytes = max(AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding), READ_BYTES * 2)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, channel, encoding, bufferBytes)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw MicrophoneException(MicrophoneProblem.Unavailable)
        }
        val gain = if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(recorder.audioSessionId) else null
        val noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(recorder.audioSessionId) else null
        gain?.enabled = true
        noise?.enabled = true
        val silence = SilenceWatch(recorder)
        val buffer = ByteArray(READ_BYTES)
        try {
            while (isActive) {
                if (isPaused()) {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    delay(PAUSE_CHECK_MS)
                    continue
                }
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.startRecording()
                    // Another app holding the microphone, a call say, keeps it from starting.
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        throw MicrophoneException(MicrophoneProblem.Busy)
                    }
                }
                val read = recorder.read(buffer, 0, buffer.size)
                // A call that comes in, or the app sent to the background, silences the microphone without an error.
                if (silence.silenced) throw MicrophoneException(MicrophoneProblem.Busy)
                when {
                    read > 0 -> onAudio(buffer.copyOf(read))
                    read < 0 -> throw MicrophoneException(MicrophoneProblem.Busy)
                }
            }
        } finally {
            silence.close()
            gain?.release()
            noise?.release()
            recorder.release()
        }
    }
}

// Android tells when it silences a recording, from Android 10. Before, a call keeps the recording from starting at all.
private class SilenceWatch(private val recorder: AudioRecord) {
    @Volatile
    var silenced = false
        private set

    private val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                silenced = configs.any { it.clientAudioSessionId == recorder.audioSessionId && it.isClientSilenced }
            }
        }.also { recorder.registerAudioRecordingCallback(Executor(Runnable::run), it) }
    } else {
        null
    }

    fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && callback != null) {
            recorder.unregisterAudioRecordingCallback(callback)
        }
    }
}
