package com.example.notitia.ui.minutes

import com.example.notitia.ui.meetings.MeetingListItem
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

val sampleSpeakers = listOf("Lucas", "Clément", "Nathan", "Adam")

// The same made-up decisions and summary for every made-up meeting, until the minutes are written on the phone
// (E3). The actions differ, so the Actions tab has something to sort.
fun sampleMinutes(meeting: MeetingListItem, now: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): Minutes =
    Minutes(
        meeting = meeting,
        speakers = sampleSpeakers,
        decisions = listOf(
            "La démo à Epitech a lieu le 15 novembre.",
            "On garde le violet du logo comme accent.",
        ),
        actions = sampleActions(meeting.id, now.toLocalDateTime(timeZone).date),
        summary = "L’équipe a validé la nouvelle identité : une interface sobre, un seul accent violet repris du logo.",
    )

// As many as the card of each made-up meeting announces, due around today so that every group shows.
private fun sampleActions(meetingId: String, today: LocalDate): List<MinutesAction> {
    val (lucas, clement, nathan, adam) = sampleSpeakers
    fun action(text: String, owner: String?, inDays: Int?, done: Boolean = false) =
        MinutesAction(text, owner, inDays?.let { today.plus(DatePeriod(days = it)) }, done)
    return when (meetingId) {
        "sample-1" -> listOf(
            action("Préparer la démo de la transcription", clement, inDays = 4),
            action("Rédiger le registre RGPD", owner = null, inDays = 17),
            action("Choisir la police de l’app", clement, inDays = null, done = true),
        )
        "sample-3" -> listOf(action("Tester l’enregistrement écran verrouillé", nathan, inDays = 1))
        "sample-4" -> listOf(
            action("Envoyer le compte rendu au mentor", clement, inDays = -1),
            action("Corriger le contraste des badges", nathan, inDays = -2),
            action("Relancer les deux témoins du CHU", adam, inDays = 0),
            action("Comparer deux modèles de transcription", lucas, inDays = null),
            action("Fixer la date de la revue du sprint 2", adam, inDays = 3, done = true),
        )
        else -> emptyList()
    }
}
