package com.example.notitia.ui.search

import com.example.notitia.ui.meetings.MeetingGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class SearchHistoryTest {

    private val paris = TimeZone.of("Europe/Paris")

    // Monday 26 October 2026, 10:00 in Paris.
    private val now = at(26, 10, 0)

    private fun at(day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime(2026, 10, day, hour, minute).toInstant(paris)

    private fun entry(text: String, at: Instant = now) = HistoryEntry(text, queryKind(text), at)

    @Test
    fun aNewSearchGoesFirst() {
        val history = listOf(entry("budget")).add(entry("registre RGPD"))
        assertEquals(listOf("registre RGPD", "budget"), history.map { it.text })
    }

    @Test
    fun aSearchLaunchedAgainMovesUpWithoutDuplicate() {
        val history = listOf(entry("Qu’a-t-on décidé pour la démo ?"), entry("budget"), entry("police de l’app"))
            .add(entry("Budget"))
            .add(entry("qu'a-t-on decide pour la demo"))
        assertEquals(listOf("qu'a-t-on decide pour la demo", "Budget", "police de l’app"), history.map { it.text })
    }

    @Test
    fun searchesAreGroupedByDayLikeTheMeetings() {
        val history = listOf(
            entry("budget", at(26, 8, 0)),
            entry("registre RGPD", at(25, 23, 50)),
            entry("police de l’app", at(23, 9, 0)),
            entry("Quand a lieu la soutenance ?", now - 40.days),
        )
        val groups = historyGroups(history, now, paris)
        assertEquals(
            listOf(MeetingGroup.Today, MeetingGroup.Yesterday, MeetingGroup.ThisWeek, MeetingGroup.InMonth(Month.SEPTEMBER, null))
                .map { HistoryGroup.Day(it) },
            groups.map { it.first },
        )
    }

    @Test
    fun theSamplesLandInTheirGroupsAtAnyTimeOfDay() {
        for (time in listOf(at(26, 0, 5), at(26, 23, 55))) {
            val groups = historyGroups(sampleHistory(time, paris), time, paris)
            assertEquals(
                listOf(MeetingGroup.Today to 2, MeetingGroup.Yesterday to 2, MeetingGroup.ThisWeek to 3)
                    .map { (group, size) -> HistoryGroup.Day(group) to size },
                groups.map { (group, entries) -> group to entries.size },
            )
        }
    }

    @Test
    fun theSamplesTellQuestionsFromWords() {
        val kinds = sampleHistory(now, paris).associate { it.text to it.kind }
        assertEquals(QueryKind.Question, kinds["Qu’a-t-on décidé pour la démo ?"])
        assertEquals(QueryKind.Words, kinds["budget"])
        assertEquals(QueryKind.Words, kinds["registre RGPD"])
    }

    @Test
    fun pinnedSearchesComeFirstAndLeaveTheirDay() {
        val budget = entry("budget", at(26, 8, 0))
        val register = entry("registre RGPD", at(25, 9, 0))
        val history = listOf(budget, register).pin(register, pinned = true)
        assertEquals(
            listOf(HistoryGroup.Pinned to listOf("registre RGPD"), HistoryGroup.Day(MeetingGroup.Today) to listOf("budget")),
            historyGroups(history, now, paris).map { (group, entries) -> group to entries.map { it.text } },
        )
        assertEquals(listOf(false, false), history.pin(register, pinned = false).map { it.pinned })
    }

    @Test
    fun aSearchLaunchedAgainKeepsItsPinAndItsTitle() {
        val history = listOf(entry("budget"), entry("registre RGPD"))
            .pin(entry("budget"), pinned = true)
            .rename(entry("budget"), "Budgets de l’année")
            .add(entry("Budget"))
        val top = history.first()
        assertEquals("Budget", top.text)
        assertEquals("Budgets de l’année", top.label)
        assertEquals(true, top.pinned)
        assertEquals(2, history.size)
    }

    @Test
    fun anEmptyOrUnchangedTitleGivesTheTextBack() {
        val history = listOf(entry("budget")).rename(entry("budget"), "Budgets")
        assertEquals("Budgets", history.single().label)
        assertEquals("budget", history.rename(entry("budget"), "  ").single().label)
        assertEquals(null, history.rename(entry("budget"), "budget").single().title)
    }

    @Test
    fun removeTakesOutOnlyThatSearch() {
        val history = listOf(entry("budget"), entry("registre RGPD"), entry("police de l’app"))
        assertEquals(listOf("budget", "police de l’app"), history.remove(entry("Registre rgpd")).map { it.text })
    }
}
