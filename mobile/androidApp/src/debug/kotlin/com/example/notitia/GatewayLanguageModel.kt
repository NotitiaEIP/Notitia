package com.example.notitia

import android.util.Log
import com.example.notitia.ai.LanguageModel
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "NotitiaGateway"
private const val CHAT_MODEL = "notitia-smart"
private const val EMBED_MODEL = "notitia-embed"
private const val TEMPERATURE = 0.3
private const val MAX_TOKENS = 1024
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000

class GatewayLanguageModel(private val baseUrl: String, private val key: String) : LanguageModel {

    override suspend fun embed(texts: List<String>): List<List<Double>> {
        val body = JSONObject().put("model", EMBED_MODEL).put("input", JSONArray(texts))
        val data = post("/v1/embeddings", body).getJSONArray("data")
        check(data.length() == texts.size) { "${data.length()} vectors for ${texts.size} texts" }
        return List(data.length()) { data.getJSONObject(it) }
            .sortedBy { it.getInt("index") }
            .map { item ->
                val vector = item.getJSONArray("embedding")
                List(vector.length()) { vector.getDouble(it) }
            }
    }

    override suspend fun complete(system: String, prompt: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject()
            .put("model", CHAT_MODEL)
            .put("messages", messages)
            .put("temperature", TEMPERATURE)
            .put("max_tokens", MAX_TOKENS)
        return post("/v1/chat/completions", body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    // Logs a path, a status or a type of error, never a message: it could quote what was asked (product rule 1).
    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "$path answered HTTP $status")
                error("HTTP $status")
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
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
