package com.example.notitia

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "NotitiaGateway"
private const val CHAT_MODEL = "notitia-smart"
private const val EMBED_MODEL = "notitia-embed"
private const val TRANSCRIBE_MODEL = "notitia-transcribe"
private const val TEMPERATURE = 0.3
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000
private const val TOO_MANY_REQUESTS = 429

// What the gateway made of a request. Unreachable: no network, too slow, the gateway busy or down, worth asking again
// later. Refused: an answer that will not change by asking again, a key refused or a reply unreadable.
sealed interface Outcome<out T> {
    data class Done<T>(val value: T) : Outcome<T>

    data object Unreachable : Outcome<Nothing>

    data object Refused : Outcome<Nothing>
}

// The team's test gateway (T-30), a server that speaks as OpenAI's does, in debug builds only: release builds stay off
// the network. Blocking, so never on the main thread. It logs a path, a status or a type of error, never a body,
// which could quote what was said (product rule 1).
class Gateway(private val baseUrl: String, private val key: String) {

    fun complete(system: String, prompt: String, maxTokens: Int): Outcome<String> {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject()
            .put("model", CHAT_MODEL)
            .put("messages", messages)
            .put("temperature", TEMPERATURE)
            .put("max_tokens", maxTokens)
        return postJson("/v1/chat/completions", body) { reply ->
            reply.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    // One vector per text, in the same order.
    fun embed(texts: List<String>): Outcome<List<List<Double>>> {
        val body = JSONObject().put("model", EMBED_MODEL).put("input", JSONArray(texts))
        return postJson("/v1/embeddings", body) { reply ->
            val data = reply.getJSONArray("data")
            if (data.length() != texts.size) throw JSONException("not one vector per text")
            List(data.length()) { data.getJSONObject(it) }
                .sortedBy { it.getInt("index") }
                .map { item ->
                    val vector = item.getJSONArray("embedding")
                    List(vector.length()) { vector.getDouble(it) }
                }
        }
    }

    // The reply as the server writes it: in verbose_json, the sentences with their times in seconds.
    fun transcribe(wav: ByteArray, language: String, previous: String): Outcome<JSONObject> {
        val boundary = "notitia${System.nanoTime()}"
        val fields = buildMap {
            put("model", TRANSCRIBE_MODEL)
            put("language", language)
            put("response_format", "verbose_json")
            if (previous.isNotEmpty()) put("prompt", previous)
        }
        return post("/v1/audio/transcriptions", "multipart/form-data; boundary=$boundary", { out ->
            fields.forEach { (name, value) ->
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            out.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n").toByteArray(),
            )
            out.write(wav)
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }) { it }
    }

    private fun <T> postJson(path: String, body: JSONObject, read: (JSONObject) -> T): Outcome<T> =
        post(path, "application/json", { it.write(body.toString().toByteArray()) }, read)

    private fun <T> post(path: String, type: String, write: (OutputStream) -> Unit, read: (JSONObject) -> T): Outcome<T> {
        val connection = try {
            URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        } catch (_: MalformedURLException) {
            Log.w(TAG, "the address of the gateway is not one")
            return Outcome.Refused
        }
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", type)
            connection.outputStream.buffered().use(write)
            val status = connection.responseCode
            when {
                status == TOO_MANY_REQUESTS || status >= HttpURLConnection.HTTP_INTERNAL_ERROR -> {
                    Log.w(TAG, "$path answered HTTP $status, worth another try")
                    Outcome.Unreachable
                }
                status != HttpURLConnection.HTTP_OK -> {
                    Log.w(TAG, "$path answered HTTP $status")
                    Outcome.Refused
                }
                else -> Outcome.Done(read(JSONObject(connection.inputStream.bufferedReader().use { it.readText() })))
            }
        } catch (e: IOException) {
            Log.w(TAG, "$path failed: ${e.javaClass.simpleName}")
            Outcome.Unreachable
        } catch (_: JSONException) {
            Log.w(TAG, "$path sent an unreadable reply")
            Outcome.Refused
        } finally {
            connection.disconnect()
        }
    }
}
