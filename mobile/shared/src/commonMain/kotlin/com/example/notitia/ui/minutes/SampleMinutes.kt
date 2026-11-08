package com.example.notitia.ui.minutes

import com.example.notitia.ui.meetings.MeetingListItem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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

// As many as the card of each made-up meeting announces, due around today so that every group shows, and two to
// complete. One has no sentence, as when the model gives no time.
private fun sampleActions(meetingId: String, today: LocalDate): List<MinutesAction> {
    val (lucas, clement, nathan, adam) = sampleSpeakers
    fun action(text: String, owner: String?, inDays: Int?, said: ActionSource?, done: Boolean = false) =
        MinutesAction(text, owner, inDays?.let { today.plus(DatePeriod(days = it)) }, done, said)
    fun said(quote: String, by: String, at: Duration) = ActionSource(quote, by, at)
    return when (meetingId) {
        "sample-1" -> listOf(
            action(
                "Préparer la démo de la transcription", clement, inDays = 4,
                said("Je m’occupe de la démo de la transcription, je l’ai pour lundi.", clement, 12.minutes + 4.seconds),
            ),
            action("Rédiger le registre RGPD", owner = null, inDays = 17, said = null),
            action(
                "Choisir la police de l’app", clement, inDays = null,
                said("Clément, tu tranches pour la police ?", nathan, 31.minutes + 40.seconds), done = true,
            ),
        )
        "sample-3" -> listOf(
            action(
                "Tester l’enregistrement écran verrouillé", nathan, inDays = 1,
                said("Je teste l’écran verrouillé demain sur mon téléphone.", nathan, 47.minutes + 12.seconds),
            ),
        )
        "sample-4" -> listOf(
            action(
                "Envoyer le compte rendu au mentor", clement, inDays = -1,
                said("J’envoie le compte rendu au mentor avant jeudi.", clement, 58.minutes + 30.seconds),
            ),
            action(
                "Corriger le contraste des badges", nathan, inDays = -2,
                said("Nathan, tu peux reprendre le contraste des badges ?", adam, 21.minutes + 5.seconds),
            ),
            action(
                "Relancer les deux témoins du CHU", adam, inDays = 0,
                said("Je relance les deux témoins du CHU.", adam, 40.minutes + 51.seconds),
            ),
            action(
                "Comparer deux modèles de transcription", lucas, inDays = null,
                said("Il faudrait comparer deux modèles, je peux le faire.", lucas, 9.minutes + 18.seconds),
            ),
            action(
                "Fixer la date de la revue du sprint 2", adam, inDays = 10,
                said("Adam, tu nous fixes la date de la prochaine revue ?", clement, 1.hours + 2.minutes),
            ),
        )
        else -> emptyList()
    }
}
