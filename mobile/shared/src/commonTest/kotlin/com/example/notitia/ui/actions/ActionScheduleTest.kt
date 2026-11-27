package com.example.notitia.ui.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

// Where an action goes on the board is the core's rule, tested in Rust (core/src/actions.rs). What stays here is what
// the sheet offers.
class ActionScheduleTest {

    // Monday 26 October 2026.
    private val today = LocalDate(2026, 10, 26)

    private fun inDays(days: Int) = today.plus(DatePeriod(days = days))

    @Test
    fun aDueDateRunsFromTheMeetingDayToAYearAfterToday() {
        val range = dueDateRange(meetingDay = inDays(-3), today = today)
        assertEquals(inDays(-3), range.start)
        assertEquals(LocalDate(2027, 10, 26), range.endInclusive)
        assertEquals(false, inDays(-4) in range)
    }
}
