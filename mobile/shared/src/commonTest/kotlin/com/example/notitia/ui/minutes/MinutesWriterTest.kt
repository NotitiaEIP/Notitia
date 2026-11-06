package com.example.notitia.ui.minutes

import com.example.notitia.ai.LanguageModel
import com.example.notitia.ui.recording.TranscriptLine
import com.example.notitia.ui.search.runNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.LocalDate

class MinutesWriterTest {

    private val day = LocalDate(2026, 11, 6)

    private class FakeModel(private val reply: String) : LanguageModel {
        val prompts = mutableListOf<String>()

        override suspend fun embed(texts: List<String>): List<List<Double>> = error("not used")

        override suspend fun complete(system: String, prompt: String): String {
            prompts += prompt
            return reply
        }
    }

    @Test
    fun thePromptGivesTheDayAndTheTimedLinesButNotTheFailedOnes() {
        val prompt = minutesPrompt(
            listOf(
                TranscriptLine(5.seconds, "Bonjour à tous."),
                TranscriptLine(30.seconds, text = null),
                TranscriptLine(2.minutes + 3.seconds, "Lucas prépare la démo."),
            ),
            day,
        )
        assertTrue("2026-11-06 (friday)" in prompt, prompt)
        assertTrue("[00:05] Bonjour à tous." in prompt, prompt)
        assertTrue("[02:03] Lucas prépare la démo." in prompt, prompt)
        assertFalse("[00:30]" in prompt, prompt)
    }

    @Test
    fun everyKindOfLineIsRead() {
        val minutes = parseMinutes(
            """
            SUMMARY: L’équipe a préparé la démo.
            DECISION: La démo a lieu le 15 novembre.
            DECISION: On garde le violet.
            ACTION: Préparer la démo | Lucas | 2026-11-13
            ACTION: Réserver la salle | - | -
            """.trimIndent(),
        )
        assertEquals("L’équipe a préparé la démo.", minutes.summary)
        assertEquals(listOf("La démo a lieu le 15 novembre.", "On garde le violet."), minutes.decisions)
        assertEquals(
            listOf(
                MinutesAction("Préparer la démo", owner = "Lucas", due = LocalDate(2026, 11, 13), done = false),
                MinutesAction("Réserver la salle", owner = null, due = null, done = false),
            ),
            minutes.actions,
        )
    }

    @Test
    fun aDateInAnotherFormatIsDroppedButNotItsAction() {
        val action = parseMinutes("ACTION: Envoyer le devis | Adam | vendredi prochain").actions.single()
        assertEquals("Adam", action.owner)
        assertEquals(null, action.due)
    }

    @Test
    fun reasoningBulletsAndOtherLinesAreIgnored() {
        val minutes = parseMinutes(
            """
            <think>DECISION: pas une vraie décision</think>
            Voici le compte rendu :
            - DECISION: On garde le violet.
            * action: Choisir la police
            ACTION:  | Lucas | -
            """.trimIndent(),
        )
        assertEquals(listOf("On garde le violet."), minutes.decisions)
        assertEquals(listOf("Choisir la police"), minutes.actions.map { it.text })
        assertEquals("", minutes.summary)
    }

    @Test
    fun whatTheModelWritesForAnEmptyListIsNotAnItem() {
        val minutes = parseMinutes(
            """
            SUMMARY: -
            DECISION: -
            DECISION: Aucune décision n’a été prise lors de cette réunion.
            DECISION: Aucune décision formelle n'a été prise.
            DECISION: Pas de décision.
            DECISION: Néant
            ACTION: -
            ACTION: Aucune action. | - | -
            ACTION: Aucune tâche attribuée.
            """.trimIndent(),
        )
        assertEquals(WrittenMinutes("", emptyList(), emptyList()), minutes)
    }

    @Test
    fun aRealDecisionThatStartsWithAucuneIsKept() {
        val minutes = parseMinutes(
            """
            DECISION: Aucune action marketing avant janvier.
            DECISION: Aucun budget supplémentaire n’est accordé.
            ACTION: Rien changer au logo avant la démo | Adam | -
            """.trimIndent(),
        )
        assertEquals(
            listOf("Aucune action marketing avant janvier.", "Aucun budget supplémentaire n’est accordé."),
            minutes.decisions,
        )
        assertEquals(listOf("Rien changer au logo avant la démo"), minutes.actions.map { it.text })
    }

    @Test
    fun aMeetingWithNothingToKeepGivesEmptyMinutes() {
        assertEquals(WrittenMinutes("", emptyList(), emptyList()), parseMinutes(""))
    }

    @Test
    fun theWriterSendsTheTranscriptAndReadsTheReply() {
        val model = FakeModel("SUMMARY: Point rapide.\nACTION: Relire la PR | Clément | -")
        val lines = listOf(TranscriptLine(0.seconds, "Clément relit la PR."))
        val minutes = runNow { LanguageModelMinutesWriter(model).write(lines, day) }
        assertTrue("Clément relit la PR." in model.prompts.single())
        assertEquals("Point rapide.", minutes.summary)
        assertEquals("Clément", minutes.actions.single().owner)
    }
}
