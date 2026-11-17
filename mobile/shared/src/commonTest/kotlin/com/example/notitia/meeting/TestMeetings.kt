package com.example.notitia.meeting

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// The meetings the tests start from, made up: the same as those of the core's own tests.
internal fun meeting(id: String, minutes: MinutesState = written("Préparer la démo")) = Meeting(
    id = id,
    startedAt = Instant.parse("2026-11-10T09:30:00Z"),
    duration = 30.minutes,
    modifiedAt = Instant.parse("2026-11-10T10:00:00Z"),
    device = "phone-1",
    minutes = minutes,
)

// Each action known by its rank, as in a meeting kept before the core.
internal fun written(vararg actions: String) = MinutesState.Written(
    WrittenMinutes(
        summary = "",
        decisions = emptyList(),
        actions = actions.mapIndexed { rank, text -> MinutesAction(rank.toString(), text, owner = null, due = null, done = false) },
    ),
)
