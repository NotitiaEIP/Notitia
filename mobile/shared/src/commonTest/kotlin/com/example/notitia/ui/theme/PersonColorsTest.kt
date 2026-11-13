package com.example.notitia.ui.theme

import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.WrittenMinutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PersonColorsTest {

    private fun meeting(day: Int, speakers: List<String>, owners: List<String> = emptyList()): Meeting {
        val startedAt = Instant.parse("2026-10-${day}T10:00:00Z")
        val actions = owners.map { MinutesAction("Relire", it, due = null, done = false) }
        return Meeting(
            id = "m$day",
            startedAt = startedAt,
            duration = 30.minutes,
            modifiedAt = startedAt,
            device = "test",
            speakers = speakers,
            minutes = MinutesState.Written(WrittenMinutes("", emptyList(), actions)),
        )
    }

    private val ten = List(PERSON_COLORS + 1) { "Personne $it" }

    @Test
    fun peopleTakeTheColoursInTheOrderTheyFirstAppearOldestMeetingFirst() {
        val newer = meeting(20, listOf("Lucas", "Nathan", "Adam"))
        val older = meeting(10, listOf("Clément", "Lucas"))
        val colors = personColors(listOf(newer, older))
        assertEquals(listOf(0, 1, 2, 3), listOf("Clément", "Lucas", "Nathan", "Adam").map(colors::of))
    }

    @Test
    fun aNameKeepsItsColourWhateverItsCaseAccentsOrSpaces() {
        val colors = personColors(listOf(meeting(10, listOf("Lucas", "Clément"))))
        assertEquals(1, colors.of("  clement "))
        assertEquals(1, colors.of("CLÉMENT"))
    }

    @Test
    fun anOwnerNamedByTheMinutesGetsAColourToo() {
        val colors = personColors(listOf(meeting(10, listOf("Clément"), owners = listOf("Zoé"))))
        assertEquals(1, colors.of("Zoé"))
    }

    @Test
    fun theTenthPersonStartsTheColoursAgain() {
        assertEquals(0, personColors(listOf(meeting(10, ten))).of(ten.last()))
    }

    @Test
    fun inAMeetingTheSecondOfTwoAlikeTakesTheNextColourThereOnly() {
        val colors = personColors(listOf(meeting(10, ten)))
        val inMeeting = colors.inMeeting(listOf(ten.first(), ten.last()))
        assertEquals(listOf(0, 1), listOf(ten.first(), ten.last()).map(inMeeting::of))
        assertEquals(0, colors.of(ten.last()))
    }

    @Test
    fun someoneWhoDidNotSpeakKeepsTheirOwnColourInAMeeting() {
        val colors = personColors(listOf(meeting(10, ten)))
        assertEquals(2, colors.inMeeting(listOf(ten[1])).of(ten[2]))
    }

    @Test
    fun eachThemeHasOneSpeakerColourPerPlace() {
        assertEquals(PERSON_COLORS, DarkColors.speakers.size)
        assertEquals(PERSON_COLORS, LightColors.speakers.size)
    }
}
