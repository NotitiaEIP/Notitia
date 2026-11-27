package com.example.notitia.ui.actions

import com.example.notitia.meeting.DueGroup
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.group_late
import notitia.shared.generated.resources.group_later
import notitia.shared.generated.resources.group_next_days
import notitia.shared.generated.resources.group_today
import notitia.shared.generated.resources.group_tomorrow
import org.jetbrains.compose.resources.StringResource

val DueGroup.title: StringResource
    get() = when (this) {
        DueGroup.Late -> Res.string.group_late
        DueGroup.Today -> Res.string.group_today
        DueGroup.Tomorrow -> Res.string.group_tomorrow
        DueGroup.NextDays -> Res.string.group_next_days
        DueGroup.Later -> Res.string.group_later
    }

// Nothing is due before the meeting that agreed to it, and Notitia follows a working year, not a lifetime.
fun dueDateRange(meetingDay: LocalDate, today: LocalDate): ClosedRange<LocalDate> =
    meetingDay..today.plus(DatePeriod(years = 1))
