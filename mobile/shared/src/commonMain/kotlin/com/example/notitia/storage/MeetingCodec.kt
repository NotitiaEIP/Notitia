package com.example.notitia.storage

import com.example.notitia.meeting.Meeting
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Raised when a change would make an older app misread a newer file.
const val MEETING_FORMAT_VERSION = 1

@Serializable
private class MeetingFile(val version: Int, val meeting: Meeting)

// A file this app cannot read gives null rather than an error: one bad meeting must not hide the others.
object MeetingCodec {
    // A field added by a newer app of the same version is skipped, not refused.
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(meeting: Meeting): ByteArray =
        json.encodeToString(MeetingFile.serializer(), MeetingFile(MEETING_FORMAT_VERSION, meeting)).encodeToByteArray()

    // Catching IllegalArgumentException covers SerializationException, and a file that is not a JSON object.
    fun decode(bytes: ByteArray): Meeting? = try {
        val file = json.parseToJsonElement(bytes.decodeToString())
        val version = file.jsonObject["version"]?.jsonPrimitive?.intOrNull
        if (version == null || version > MEETING_FORMAT_VERSION) {
            null
        } else {
            json.decodeFromJsonElement(MeetingFile.serializer(), file).meeting
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}
