package com.example.notitia.ui.search

import com.example.notitia.ai.LanguageModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class RagAnswerEngineTest {

    private val demo = Passage("sample-1", 12.minutes, "Lucas", "On garde le 15 novembre.")
    private val rehearsal = Passage("sample-1", 13.minutes, "Clément", "Je prépare la répétition.")
    private val sources = listOf(demo, rehearsal)

    @Test
    fun citationsBecomePartsAndTheTextAfterTheLastOneHasNoSource() {
        val answer = assertNotNull(parseAnswer("La démo a lieu le 15 novembre [1], et Clément la prépare [2].", sources))
        assertEquals(
            listOf(
                AnswerPart("La démo a lieu le 15 novembre", source = 0),
                AnswerPart(", et Clément la prépare", source = 1),
                AnswerPart(".", source = null),
            ),
            answer.parts,
        )
        assertEquals(sources, answer.sources)
    }

    @Test
    fun sourcesAreNumberedInTheOrderTheyAreCited() {
        val answer = assertNotNull(parseAnswer("Clément la prépare [2], le 15 novembre [1], avec Clément [2]", sources))
        assertEquals(listOf(rehearsal, demo), answer.sources)
        assertEquals(listOf(0, 1, 0), answer.parts.map { it.source })
    }

    @Test
    fun aSourceTheModelMadeUpLosesItsCitationButNotItsWords() {
        val answer = assertNotNull(parseAnswer("La démo a lieu le 15 novembre [1], à Epitech [7].", sources))
        assertEquals(listOf(demo), answer.sources)
        assertEquals(
            listOf(AnswerPart("La démo a lieu le 15 novembre", source = 0), AnswerPart(", à Epitech.", source = null)),
            answer.parts,
        )
    }

    @Test
    fun oneCitationCanNameSeveralSources() {
        val answer = assertNotNull(parseAnswer("La démo se prépare [1, 2].", sources))
        assertEquals(sources, answer.sources)
        assertEquals(listOf(0, 1, null), answer.parts.map { it.source })
    }

    @Test
    fun anAnswerThatCitesNothingIsNotKept() {
        assertNull(parseAnswer("NOTHING", sources))
        assertNull(parseAnswer("La démo a lieu le 15 novembre.", sources))
        assertNull(parseAnswer("La démo a lieu à Epitech [7].", sources))
        assertNull(parseAnswer("", sources))
    }

    @Test
    fun followUpsAndReasoningStayOutOfTheAnswer() {
        val reply = """
            <think>Le passage 1 répond [2].</think>
            La démo a lieu le 15 novembre [1].
            FOLLOW-UP: Qui prépare la répétition ?
            follow-up: Où a lieu la démo ?
            FOLLOW-UP: Une troisième question ?
        """.trimIndent()
        val answer = assertNotNull(parseAnswer(reply, sources))
        assertEquals(listOf(demo), answer.sources)
        assertEquals(listOf("La démo a lieu le 15 novembre", "."), answer.parts.map { it.text })
        assertEquals(listOf("Qui prépare la répétition ?", "Où a lieu la démo ?"), answer.followUps)
    }

    @Test
    fun theClosestPassagesComeFirstAndTheirNumberIsLimited() {
        val others = List(5) { Passage("sample-2", it.minutes, "Adam", "Autre $it") to listOf(0.0, 1.0) }
        val found = closestPassages(listOf(1.0, 0.0), others + (demo to listOf(2.0, 0.0)))
        assertEquals(demo, found.first())
        assertEquals(4, found.size)
    }

    @Test
    fun onlyTheQuestionAndThePassagesReachTheModel() {
        val model = RecordingModel()
        val engine = RagAnswerEngine(model, passages = sources)
        val question = "Qui prépare la démo ?"

        val found = runNow { engine.findSources(question) }
        assertEquals(listOf(question, demo.text, rehearsal.text), model.embedded)

        val answer = runNow { engine.writeAnswer(question, found) }
        assertEquals(1, assertNotNull(answer).sources.size)
        assertEquals(answerPrompt(question, found), model.prompt)
        assertTrue(rehearsal.text in model.prompt)
    }

    private class RecordingModel : LanguageModel {
        var embedded = emptyList<String>()
        var prompt = ""

        override suspend fun embed(texts: List<String>): List<List<Double>> {
            embedded = texts
            return texts.map { listOf(1.0, 0.0) }
        }

        override suspend fun complete(system: String, prompt: String): String {
            this.prompt = prompt
            return "Clément la prépare [1]."
        }
    }
}
