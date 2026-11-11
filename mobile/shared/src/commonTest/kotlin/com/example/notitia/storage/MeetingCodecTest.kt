package com.example.notitia.storage

import com.example.notitia.meeting.ActionSource
import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.TranscriptLine
import com.example.notitia.meeting.WrittenMinutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class MeetingCodecTest {

    private val meeting = Meeting(
        id = "recorded-1",
        startedAt = Instant.parse("2026-11-10T09:30:00Z"),
        duration = 42.minutes,
        modifiedAt = Instant.parse("2026-11-10T10:15:00Z"),
        device = "phone-1",
        title = "Revue de sprint",
        speakers = listOf("Lucas", "Nathan"),
        transcript = listOf(TranscriptLine(0.seconds, "Bonjour à tous."), TranscriptLine(30.seconds, text = null)),
        minutes = MinutesState.Written(
            WrittenMinutes(
                summary = "Point sur la démo.",
                decisions = listOf("La démo a lieu le 15 novembre."),
                actions = listOf(
                    MinutesAction(
                        "Préparer la démo", "Lucas", LocalDate(2026, 11, 14), done = true,
                        source = ActionSource("Je prépare la démo.", speaker = null, at = 12.minutes + 4.seconds),
                    ),
                    MinutesAction("Rédiger le registre RGPD", owner = null, due = null, done = false),
                ),
            ),
        ),
    )

    @Test
    fun aMeetingComesBackAsItWasWritten() {
        assertEquals(meeting, MeetingCodec.decode(MeetingCodec.encode(meeting)))
    }

    @Test
    fun everyStateOfTheMinutesComesBack() {
        listOf(MinutesState.Writing, MinutesState.Failed, MinutesState.Unavailable).forEach { state ->
            val withState = meeting.copy(minutes = state)
            assertEquals(withState, MeetingCodec.decode(MeetingCodec.encode(withState)))
        }
    }

    @Test
    fun aFileFromANewerFormatIsNotRead() {
        val newer = MeetingCodec.encode(meeting).decodeToString()
            .replace("\"version\":$MEETING_FORMAT_VERSION", "\"version\":${MEETING_FORMAT_VERSION + 1}")
        assertNull(MeetingCodec.decode(newer.encodeToByteArray()))
    }

    @Test
    fun aFieldAddedByANewerAppIsSkipped() {
        val withExtra = MeetingCodec.encode(meeting).decodeToString().replaceFirst("{", "{\"color\":\"violet\",")
        assertEquals(meeting, MeetingCodec.decode(withExtra.encodeToByteArray()))
    }

    @Test
    fun aDamagedFileIsNotRead() {
        val bytes = MeetingCodec.encode(meeting)
        assertNull(MeetingCodec.decode(bytes.copyOf(bytes.size / 2)))
        assertNull(MeetingCodec.decode("[]".encodeToByteArray()))
        assertNull(MeetingCodec.decode(ByteArray(0)))
    }
}
