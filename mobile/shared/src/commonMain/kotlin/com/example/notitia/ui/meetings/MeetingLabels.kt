package com.example.notitia.ui.meetings

import androidx.compose.runtime.Composable
import kotlin.time.Duration
import kotlinx.datetime.LocalDate
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.duration_hours
import notitia.shared.generated.resources.duration_hours_minutes
import notitia.shared.generated.resources.duration_minutes
import notitia.shared.generated.resources.meeting_day_month
import notitia.shared.generated.resources.months_short
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun shortDateLabel(date: LocalDate): String =
    stringResource(Res.string.meeting_day_month, date.day, stringArrayResource(Res.array.months_short)[date.month.ordinal])

@Composable
fun lengthLabel(duration: Duration): String {
    val length = meetingLength(duration)
    return when {
        length.hours == 0 -> stringResource(Res.string.duration_minutes, length.minutes)
        length.minutes == 0 -> stringResource(Res.string.duration_hours, length.hours)
        else -> stringResource(Res.string.duration_hours_minutes, length.hours, length.minutes.twoDigits())
    }
}
