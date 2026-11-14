package com.example.notitia.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

internal fun today(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

internal fun untilMidnight(now: Instant, zone: TimeZone): Duration =
    now.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone) - now

// An app left open overnight must not keep calling yesterday « Aujourd'hui ». The day also turns when the app comes
// back, since a phone asleep may not run the delay on time. The delay is worked out again each turn: a wake-up a
// little early only waits the rest.
@Composable
fun rememberToday(): LocalDate {
    var day by remember { mutableStateOf(today()) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { day = today() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(untilMidnight(Clock.System.now(), TimeZone.currentSystemDefault()))
            day = today()
        }
    }
    return day
}
