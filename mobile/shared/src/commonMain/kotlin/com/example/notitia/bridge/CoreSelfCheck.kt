package com.example.notitia.bridge

import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.storage.MEETING_FORMAT_VERSION
import com.example.notitia.storage.MeetingCodec
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

private const val STARTED_AT_MS = 1_794_303_000_000

// The core reads formats the Kotlin codec does not write yet: only a version far ahead is newer for both.
private const val FORMAT_NO_APP_READS = 1_000

// Made up, so the marker and the file say nothing of a real meeting.
private val sample = Meeting(
    id = "core-self-check",
    startedAt = Instant.fromEpochMilliseconds(STARTED_AT_MS),
    duration = 30.minutes,
    modifiedAt = Instant.fromEpochMilliseconds(STARTED_AT_MS),
    device = "core-self-check",
    title = "Self-check",
    minutes = MinutesState.Unavailable,
)

// Proves the bridge on a device, the same way on Android and on the iPhone, until the storage runs on the core
// (T-57): the core reads a file the Kotlin codec wrote, refuses what it must, and calls the app back.
class CoreSelfCheck(
    private val core: NotitiaCore,
    private val directory: Path = SystemTemporaryDirectory,
    private val files: FileSystem = SystemFileSystem,
) {
    // Fixed words, safe to log: the name of a step, never what a file holds.
    fun run(): String = firstFailure()?.let { "core bridge: failed at $it" } ?: "core bridge: ok"

    private fun firstFailure(): String? {
        val meeting = Path(directory, "core-self-check.notitia")
        val newer = Path(directory, "core-self-check-newer.notitia")
        return try {
            val written = MeetingCodec.encode(sample)
            write(meeting, written)
            write(
                newer,
                written.decodeToString()
                    .replace("\"version\":$MEETING_FORMAT_VERSION", "\"version\":$FORMAT_NO_APP_READS")
                    .encodeToByteArray(),
            )
            val expected = MeetingSummary(sample.id, sample.title, STARTED_AT_MS)
            when {
                core.meetingSummary(meeting.toString()) != CoreOk(expected) -> "read"
                core.meetingSummary(newer.toString()) != CoreFailed<MeetingSummary>(CoreFailure.NewerFormat) -> "newer format"
                core.meetingSummary(Path(directory, "core-self-check-missing.notitia").toString()) !=
                    CoreFailed<MeetingSummary>(CoreFailure.Unreadable) -> "missing file"
                core.ask({ question -> "$question pong" }, "ping") != "ping pong" -> "host"
                else -> null
            }
        } catch (_: Exception) {
            "exception"
        } finally {
            files.delete(meeting, mustExist = false)
            files.delete(newer, mustExist = false)
        }
    }

    private fun write(file: Path, bytes: ByteArray) = files.sink(file).buffered().use { it.write(bytes) }
}
