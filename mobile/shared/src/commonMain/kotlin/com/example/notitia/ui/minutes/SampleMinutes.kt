package com.example.notitia.ui.minutes

import com.example.notitia.meeting.ActionSource
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.WrittenMinutes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

val sampleSpeakers = listOf("Lucas", "Clément", "Nathan", "Adam")

// The same made-up decisions for every made-up meeting. The summaries and actions differ, so the list of meetings
// can be told apart and the Actions tab has something to sort.
fun sampleWrittenMinutes(meetingId: String, today: LocalDate): WrittenMinutes = WrittenMinutes(
    summary = sampleSummaries.getValue(meetingId),
    decisions = listOf(
        "La démo à Epitech a lieu le 15 novembre.",
        "On garde le violet du logo comme accent.",
    ),
    actions = sampleActions(meetingId, today),
)

private val sampleSummaries = mapOf(
    "sample-1" to "Le prototype enregistre et transcrit sur le téléphone. La démo de la transcription est " +
        "prête pour lundi ; il reste le registre RGPD à rédiger.",
    "sample-2" to "Sophie, cadre de santé, veut relire le compte rendu avant de le partager, et que l’audio " +
        "ne quitte jamais le téléphone.",
    "sample-3" to "L’accueil garde un seul bouton Enregistrer, en bas de l’écran. Nathan teste l’enregistrement " +
        "écran verrouillé avant de figer le parcours.",
    "sample-4" to "Sprint 1 bouclé avec deux jours de retard sur les badges. Le compte rendu part au mentor et " +
        "la revue du sprint 2 reste à fixer.",
    "sample-5" to "Notitia enregistre, transcrit et résume les réunions sans rien envoyer hors du téléphone. " +
        "Premier jalon : une réunion d’une heure, écran verrouillé.",
)

// Due around today so that every group shows, and two to complete. One has no sentence, as when the model gives
// no time.
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
