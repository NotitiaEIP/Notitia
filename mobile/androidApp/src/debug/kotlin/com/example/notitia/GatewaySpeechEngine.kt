package com.example.notitia

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.example.notitia.ai.SpeechEngine
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "NotitiaGateway"
private const val TRANSCRIBE_MODEL = "notitia-transcribe"
private const val LANGUAGE = "fr"
private const val SAMPLE_RATE = 16_000
private const val READ_BYTES = 3_200
private const val PAUSE_CHECK_MS = 100L
private const val MAX_ATTEMPTS = 3
private const val RETRY_DELAY_MS = 2_000L
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000
private const val TOO_MANY_REQUESTS = 429

class GatewaySpeechEngine(
    private val context: Context,
    private val baseUrl: String,
    private val key: String,
) : SpeechEngine {

    override fun canListen(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // canListen is checked by the caller before listening.
    @SuppressLint("MissingPermission")
    override suspend fun listen(isPaused: () -> Boolean, onAudio: (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val channel = AudioFormat.CHANNEL_IN_MONO
        val bufferBytes = max(AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding), READ_BYTES * 2)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, channel, encoding, bufferBytes)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "microphone unavailable")
            recorder.release()
            return@withContext
        }
        val gain = if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(recorder.audioSessionId) else null
        val noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(recorder.audioSessionId) else null
        gain?.enabled = true
        noise?.enabled = true
        val buffer = ByteArray(READ_BYTES)
        try {
            while (isActive) {
                if (isPaused()) {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    delay(PAUSE_CHECK_MS)
                } else {
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) recorder.startRecording()
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) onAudio(buffer.copyOf(read))
                }
            }
        } finally {
            gain?.release()
            noise?.release()
            recorder.release()
        }
    }

    override suspend fun transcribe(wav: ByteArray, previousText: String): String = withContext(Dispatchers.IO) {
        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return@withContext send(wav, previousText)
            } catch (_: IOException) {
                delay((attempt + 1) * RETRY_DELAY_MS)
            }
        }
        send(wav, previousText)
    }

    // Logs a status or a type of error, never a message: it could quote what was said (product rule 1).
    private fun send(wav: ByteArray, previousText: String): String {
        val path = "/v1/audio/transcriptions"
        val boundary = "notitia${System.nanoTime()}"
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.buffered().use { out ->
                fun field(name: String, value: String) = out.write(
                    "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(),
                )
                field("model", TRANSCRIBE_MODEL)
                field("language", LANGUAGE)
                field("response_format", "json")
                if (previousText.isNotEmpty()) field("prompt", previousText)
                out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                        "Content-Type: audio/wav\r\n\r\n").toByteArray(),
                )
                out.write(wav)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = connection.responseCode
            if (status == TOO_MANY_REQUESTS || status >= HttpURLConnection.HTTP_INTERNAL_ERROR) {
                Log.w(TAG, "$path answered HTTP $status, worth another try")
                throw IOException("HTTP $status")
            }
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "$path answered HTTP $status")
                error("HTTP $status")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("text")
        } catch (e: IOException) {
            Log.w(TAG, "$path failed: ${e.javaClass.simpleName}")
            throw e
        } catch (e: JSONException) {
            Log.w(TAG, "$path sent an unreadable reply")
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
