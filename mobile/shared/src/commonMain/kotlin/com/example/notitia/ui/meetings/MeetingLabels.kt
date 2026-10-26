package com.example.notitia.ui.meetings

import androidx.compose.runtime.Composable
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.duration_hours
import notitia.shared.generated.resources.duration_hours_minutes
import notitia.shared.generated.resources.duration_minutes
import notitia.shared.generated.resources.group_today
import notitia.shared.generated.resources.group_yesterday
import notitia.shared.generated.resources.meeting_day_month
import notitia.shared.generated.resources.minutes_when
import notitia.shared.generated.resources.months_short
import notitia.shared.generated.resources.weekdays
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun shortDateLabel(date: LocalDate): String =
    stringResource(Res.string.meeting_day_month, date.day, stringArrayResource(Res.array.months_short)[date.month.ordinal])

// "Aujourd’hui, 14:02", "Lundi, 09:30", "3 oct., 11:00".
@Composable
fun meetingWhenLabel(startedAt: Instant, now: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val day = when (val meetingDay = meetingDay(startedAt, now, timeZone)) {
        is MeetingDay.Today -> stringResource(Res.string.group_today)
        is MeetingDay.Yesterday -> stringResource(Res.string.group_yesterday)
        is MeetingDay.ThisWeek -> stringArrayResource(Res.array.weekdays)[meetingDay.dayOfWeek.ordinal]
        is MeetingDay.Earlier -> shortDateLabel(meetingDay.date)
    }
    return stringResource(Res.string.minutes_when, day, clockTime(startedAt.toLocalDateTime(timeZone).time))
}

@Composable
fun lengthLabel(duration: Duration): String {
    val length = meetingLength(duration)
    return when {
        length.hours == 0 -> stringResource(Res.string.duration_minutes, length.minutes)
        length.minutes == 0 -> stringResource(Res.string.duration_hours, length.hours)
        else -> stringResource(Res.string.duration_hours_minutes, length.hours, length.minutes.twoDigits())
    }
}
