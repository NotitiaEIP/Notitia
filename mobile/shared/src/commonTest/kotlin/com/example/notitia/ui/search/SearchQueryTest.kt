package com.example.notitia.ui.search

import com.example.notitia.ui.meetings.sampleMeetings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class SearchQueryTest {

    @Test
    fun wordsAreSearchedAndQuestionsAsked() {
        listOf("budget", "registre RGPD", "police de l’app", "  budget  ").forEach {
            assertEquals(QueryKind.Words, queryKind(it), it)
        }
        listOf(
            "Qu’a-t-on décidé pour la démo ?",
            "qu'a-t-on décidé",
            "Qui ?",
            "Où est Lucas",
            "est-ce prêt",
            "Décisions de la semaine",
            "  Combien  ",
        ).forEach {
            assertEquals(QueryKind.Question, queryKind(it), it)
        }
    }

    @Test
    fun foldingDropsCaseAndAccentsButKeepsTheLength() {
        assertEquals("demo", fold("Démo"))
        assertEquals("ca a ete tres eleve", fold("Ça a été très élevé"))
        assertEquals("l'app", fold("l’app"))
        val text = "À Noël, l’équipe était là"
        assertEquals(text.length, fold(text).length)
    }

    @Test
    fun aWordIsFoundInTheMostRecentMeetingFirst() {
        val meetings = sampleMeetings(Clock.System.now())
        val found = findPassages("BUDGET", samplePassages)
        assertEquals(4, found.size)
        val groups = groupByMeeting(found, meetings)
        assertEquals(listOf("sample-1", "sample-4", "sample-5"), groups.map { it.first.id })
        val sprint = groups[1].second
        assertTrue(sprint[0].at < sprint[1].at)
    }

    @Test
    fun accentsAndEveryWordCount() {
        assertEquals(listOf("On garde le 15 novembre pour la démo."), findPassages("demo", samplePassages).map { it.text })
        assertEquals(1, findPassages("registre rgpd", samplePassages).size)
        assertEquals(1, findPassages("police de l'app", samplePassages).size)
        assertTrue(findPassages("budget démo", samplePassages).isEmpty())
        assertTrue(findPassages("budgte", samplePassages).isEmpty())
        assertTrue(findPassages("   ", samplePassages).isEmpty())
    }

    @Test
    fun everyOccurrenceIsHighlightedOnce() {
        assertEquals(listOf(3..8), highlights("Le budget des tests", "BUDGET"))
        assertEquals(listOf(3..7, 10..14), highlights("Le délai, délai court", "delai"))
        assertEquals(listOf(0..1, 3..8), highlights("Le budget", "budget le"))
        assertEquals(listOf(3..8), highlights("Le budget", "bud budget"))
    }

    @Test
    fun aQuestionFindsItsSampleAnswerWhateverTheTyping() {
        val answer = assertNotNull(sampleAnswer("qu'a-t-on decide pour la demo"))
        assertEquals(2, answer.sources.size)
        assertNotNull(sampleAnswer("Décisions de la semaine ?"))
        assertNull(sampleAnswer("Quand a lieu la soutenance ?"))
    }
}
