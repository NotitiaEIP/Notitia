package com.example.notitia.storage

import com.example.notitia.meeting.Meeting
import kotlin.uuid.Uuid
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

// Blocking: MeetingRepository calls it off the main thread. US-84 will encrypt by wrapping it.
interface MeetingStore {
    // Drawn once per install, never derived from the phone, so it says nothing about its owner.
    fun device(): String
    fun loadAll(): List<Meeting>
    fun save(meeting: Meeting)
}

// For previews: nothing is read, nothing is kept.
object NoMeetingStore : MeetingStore {
    override fun device() = "preview"
    override fun loadAll() = emptyList<Meeting>()
    override fun save(meeting: Meeting) = Unit
}

private const val EXTENSION = ".notitia"

// One file per meeting, so a damaged file loses one meeting and not all of them.
class FileMeetingStore(
    private val directory: Path,
    private val files: FileSystem = SystemFileSystem,
) : MeetingStore {

    override fun device(): String {
        val file = Path(directory, "device")
        if (files.exists(file)) return read(file).decodeToString()
        return Uuid.random().toString().also { write(file, it.encodeToByteArray()) }
    }

    // A file this app cannot read stays on disk: a newer app may read it again after an update.
    override fun loadAll(): List<Meeting> =
        if (!files.exists(directory)) {
            emptyList()
        } else {
            files.list(directory).filter { it.name.endsWith(EXTENSION) }.mapNotNull { MeetingCodec.decode(read(it)) }
        }

    override fun save(meeting: Meeting) = write(Path(directory, meeting.id + EXTENSION), MeetingCodec.encode(meeting))

    private fun read(file: Path): ByteArray = files.source(file).buffered().use { it.readByteArray() }

    // Written aside then moved over: an app killed mid-write leaves the previous version, never half a file.
    private fun write(file: Path, bytes: ByteArray) {
        files.createDirectories(directory)
        val draft = Path(directory, file.name + ".tmp")
        files.sink(draft).buffered().use { it.write(bytes) }
        files.atomicMove(draft, file)
    }
}
