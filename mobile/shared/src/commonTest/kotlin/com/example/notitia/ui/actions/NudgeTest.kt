package com.example.notitia.ui.actions

import com.example.notitia.ui.meetings.MeetingListItem
import com.example.notitia.ui.meetings.MeetingStatus
import com.example.notitia.ui.minutes.MinutesAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class NudgeTest {

    private val paris = TimeZone.of("Europe/Paris")

    // Monday 26 October 2026.
    private val today = LocalDate(2026, 10, 26)

    private fun meeting(title: String, day: Int, hour: Int = 10) = MeetingListItem(
        id = title,
        title = title,
        startedAt = LocalDateTime(2026, 10, day, hour, 0).toInstant(paris),
        duration = 30.minutes,
        participants = 2,
        status = MeetingStatus.Ready(actions = 1),
    )

    private fun tracked(
        text: String,
        owner: String? = "Nathan",
        due: LocalDate? = null,
        done: Boolean = false,
        meeting: MeetingListItem = meeting("Point produit", 20),
        index: Int = 0,
    ) = TrackedAction(ActionId(meeting.id, index), MinutesAction(text, owner, due, done), meeting, speaker = null)

    private val words = NudgeWords(
        hello = "Bonjour %1\$s,",
        one = "Petit rappel de la réunion « %1\$s » du %2\$s :",
        many = "Petit point sur tes actions en cours :",
        meeting = "%1\$s, %2\$s",
        line = "– %1\$s",
        lineDue = "– %1\$s, pour le %2\$s",
        thanks = "Merci !",
        date = { "${it.day}/${it.month.ordinal + 1}" },
    )

    private fun person(vararg actions: TrackedAction): PersonActions = actionsByPerson(actions.toList(), today).first()

    @Test
    fun nobodyIsNudgedAboutAnActionWithoutOwnerOrAlreadyDone() {
        assertNull(nudgeFor(tracked("Relire le devis", owner = null), paris))
        assertNull(nudgeFor(tracked("Relire le devis", done = true), paris))
    }

    @Test
    fun theActionsNobodyOwnsCannotBeNudged() {
        assertNull(nudgeFor(person(tracked("Relire le devis", owner = null)), paris))
    }

    @Test
    fun aJustTickedActionIsLeftOutAndNothingLeftMeansNoNudge() {
        val ticked = tracked("Relire le devis", done = true)
        val people = actionsByPerson(listOf(ticked), today, lingering = setOf(ticked.id))
        assertEquals(listOf(ticked), people.first().open)
        assertNull(nudgeFor(people.first(), paris))
    }

    @Test
    fun anActionNamesItsMeetingAndTheDayOfIt() {
        val nudge = nudgeFor(tracked("Envoyer la maquette", due = LocalDate(2026, 11, 7)), paris)
        assertEquals(
            Nudge(
                "Nathan",
                speaker = null,
                listOf(
                    NudgeMeeting(
                        "Point produit",
                        LocalDate(2026, 10, 20),
                        listOf(NudgeLine("Envoyer la maquette", LocalDate(2026, 11, 7))),
                    ),
                ),
            ),
            nudge,
        )
    }

    @Test
    fun theMeetingWithTheMostPressingActionComesFirstAndTheUndatedOnesLast() {
        val review = meeting("Revue", 22)
        val client = meeting("Client", 19)
        val sprint = meeting("Sprint", 21)
        val nudge = nudgeFor(
            person(
                tracked("a", due = LocalDate(2026, 10, 30), meeting = review),
                tracked("b", due = null, meeting = sprint),
                tracked("c", due = LocalDate(2026, 10, 28), meeting = client),
                tracked("d", due = LocalDate(2026, 11, 2), meeting = client, index = 1),
            ),
            paris,
        )
        assertEquals(listOf("Client", "Revue", "Sprint"), nudge?.meetings?.map { it.title })
        assertEquals(listOf("c", "d"), nudge?.meetings?.first()?.lines?.map { it.text })
    }

    @Test
    fun oneMeetingReadsAsASingleReminder() {
        val nudge = checkNotNull(nudgeFor(tracked("Envoyer la maquette.", due = LocalDate(2026, 11, 7)), paris))
        assertEquals(
            """
            Bonjour Nathan,

            Petit rappel de la réunion « Point produit » du 20/10 :
            – Envoyer la maquette, pour le 7/11

            Merci !
            """.trimIndent(),
            nudgeText(nudge, words),
        )
    }

    @Test
    fun severalMeetingsGetAHeadingEachAndAnUndatedActionNoDate() {
        val client = meeting("Client", 19)
        val nudge = checkNotNull(
            nudgeFor(
                person(
                    tracked("Relire le devis", due = LocalDate(2026, 10, 28)),
                    tracked("Appeler le fournisseur", meeting = client),
                ),
                paris,
            ),
        )
        assertEquals(
            """
            Bonjour Nathan,

            Petit point sur tes actions en cours :

            Point produit, 20/10
            – Relire le devis, pour le 28/10

            Client, 19/10
            – Appeler le fournisseur

            Merci !
            """.trimIndent(),
            nudgeText(nudge, words),
        )
    }
}
