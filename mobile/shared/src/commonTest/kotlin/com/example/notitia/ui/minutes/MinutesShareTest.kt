package com.example.notitia.ui.minutes

import com.example.notitia.meeting.MeetingStatus
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.ui.meetings.MeetingListItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class MinutesShareTest {

    private val paris = TimeZone.of("Europe/Paris")

    private val words = MinutesShareWords(
        summary = "Résumé",
        decisions = "Décisions",
        actions = "Actions",
        line = "– %1\$s",
        actionOwner = "– %1\$s (%2\$s)",
        actionDue = "– %1\$s, pour le %2\$s",
        actionOwnerDue = "– %1\$s (%2\$s), pour le %3\$s",
        date = { "${it.day}/${it.month.ordinal + 1}/${it.year}" },
    )

    // Late in the evening in Paris, already the next day in UTC: the date is the one the user lived.
    private fun minutes(
        summary: String = "",
        decisions: List<String> = emptyList(),
        actions: List<MinutesAction> = emptyList(),
    ) = Minutes(
        meeting = MeetingListItem(
            id = "point-produit",
            title = "Point produit",
            startedAt = LocalDateTime(2026, 10, 20, 23, 30).toInstant(paris),
            duration = 30.minutes,
            speakers = emptyList(),
            status = MeetingStatus.Ready(),
        ),
        speakers = emptyList(),
        decisions = decisions,
        actions = actions,
        summary = summary,
    )

    private fun action(text: String, owner: String? = null, due: LocalDate? = null) =
        MinutesAction(text, owner, due, done = false)

    @Test
    fun theSummaryComesFirstThenTheDecisionsThenOneActionPerLine() {
        val text = minutesShareText(
            minutes(
                summary = "On lance la bêta en décembre.",
                decisions = listOf("Bêta le 15 décembre", "Pas d'iPad pour l'instant"),
                actions = listOf(
                    action("Envoyer la maquette.", owner = "Nathan", due = LocalDate(2026, 11, 7)),
                    action("Relire le devis", owner = "Lucas"),
                    action("Réserver la salle", due = LocalDate(2026, 11, 2)),
                    action("Prévenir le mentor"),
                ),
            ),
            words,
            paris,
        )
        assertEquals(
            """
            Point produit
            20/10/2026

            Résumé
            On lance la bêta en décembre.

            Décisions
            – Bêta le 15 décembre
            – Pas d'iPad pour l'instant

            Actions
            – Envoyer la maquette (Nathan), pour le 7/11/2026
            – Relire le devis (Lucas)
            – Réserver la salle, pour le 2/11/2026
            – Prévenir le mentor
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun anEmptyPartIsLeftOutRatherThanAnnounced() {
        val text = minutesShareText(minutes(decisions = listOf("Bêta le 15 décembre")), words, paris)
        assertEquals(
            """
            Point produit
            20/10/2026

            Décisions
            – Bêta le 15 décembre
            """.trimIndent(),
            text,
        )
    }
}
