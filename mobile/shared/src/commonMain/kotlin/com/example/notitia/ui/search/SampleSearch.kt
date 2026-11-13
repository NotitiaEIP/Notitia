package com.example.notitia.ui.search

import com.example.notitia.ui.minutes.sampleSpeakers
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

// What the engine (E6) might take, so the loader shows until the real one exists.
val SAMPLE_SEARCH_TIME = 1400.milliseconds
val SAMPLE_WRITING_TIME = 1300.milliseconds

private val speakers = sampleSpeakers + "Alexandre"

private fun passage(meetingId: String, minutes: Int, seconds: Int, speaker: Int, text: String) =
    Passage(meetingId, minutes.minutes + seconds.seconds, speakers[speaker], text)

private val serverBudget = passage("sample-1", 8, 12, 0, "Sans budget serveur, tout tourne sur le téléphone.")
private val demoDate = passage("sample-1", 12, 24, 0, "On garde le 15 novembre pour la démo.")
private val rehearsal = passage("sample-1", 12, 31, 1, "Je prépare la répétition la semaine d’avant.")
private val register = passage("sample-1", 18, 40, 2, "Je rédige le registre RGPD avant la fin du mois.")
private val violet = passage("sample-3", 22, 10, 3, "On garde le violet du logo, c’est notre seul accent.")
private val font = passage("sample-3", 35, 2, 1, "La police de l’app sera Schibsted Grotesk.")
private val testsBudget =
    passage("sample-4", 12, 4, 3, "Le budget des tests utilisateurs reste à zéro, on recrute par l’école.")
private val domainBudget = passage("sample-4", 31, 40, 1, "On garde le budget pour le domaine notitia.fr.")
private val cloudBudget = passage("sample-5", 15, 30, 4, "On fera sans budget cloud : c’est notre argument.")

// Made-up passages until the transcriptions exist (E2). "Entretien utilisateur, CHU" has none: it is still
// being transcribed.
val samplePassages = listOf(
    serverBudget, demoDate, rehearsal, register, violet, font, testsBudget, domainBudget, cloudBudget,
)

private const val DEMO_QUESTION = "Qu’a-t-on décidé pour la démo ?"
private const val WEEK_DECISIONS = "Décisions de la semaine"
private const val REHEARSAL_QUESTION = "Qui prépare la répétition ?"

val sampleSuggestions = listOf(WEEK_DECISIONS, DEMO_QUESTION, "budget")

private val sampleAnswers = mapOf(
    DEMO_QUESTION to Answer(
        parts = listOf(
            AnswerPart("Deux décisions cette semaine : la démo à Epitech a lieu le 15 novembre", source = 0),
            AnswerPart(", et Clément prépare une répétition la semaine d’avant", source = 1),
            AnswerPart(".", source = null),
        ),
        sources = listOf(demoDate, rehearsal),
        followUps = listOf(REHEARSAL_QUESTION, WEEK_DECISIONS),
    ),
    WEEK_DECISIONS to Answer(
        parts = listOf(
            AnswerPart("La démo a été fixée au 15 novembre", source = 0),
            AnswerPart(", et le violet du logo reste le seul accent de l’app", source = 1),
            AnswerPart(".", source = null),
        ),
        sources = listOf(demoDate, violet),
        followUps = listOf(DEMO_QUESTION, REHEARSAL_QUESTION),
    ),
    REHEARSAL_QUESTION to Answer(
        parts = listOf(
            AnswerPart("Clément prépare la répétition de la démo, la semaine d’avant le 15 novembre", source = 0),
            AnswerPart(".", source = null),
        ),
        sources = listOf(rehearsal),
        followUps = listOf(DEMO_QUESTION, WEEK_DECISIONS),
    ),
)

fun sampleAnswer(question: String): Answer? =
    sampleAnswers.entries.firstOrNull { sameQuestion(it.key, question) }?.value

object SampleAnswerEngine : AnswerEngine {
    override suspend fun findSources(question: String): List<Passage> {
        delay(SAMPLE_SEARCH_TIME)
        return sampleAnswer(question)?.sources.orEmpty()
    }

    override suspend fun writeAnswer(question: String, sources: List<Passage>): Answer? {
        delay(SAMPLE_WRITING_TIME)
        return sampleAnswer(question)
    }

    // Found again in the samples, without the loader.
    override fun restoredStep(question: String): SearchStep =
        sampleAnswer(question)?.let { SearchStep.Answered(question, it) }
            ?: SearchStep.NothingFound(question, QueryKind.Question)
}
