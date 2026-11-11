package com.example.notitia.storage

import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.MinutesState
import com.example.notitia.meeting.WrittenMinutes
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal fun meeting(id: String, minutes: MinutesState = written("Préparer la démo")) = Meeting(
    id = id,
    startedAt = Instant.parse("2026-11-10T09:30:00Z"),
    duration = 30.minutes,
    modifiedAt = Instant.parse("2026-11-10T10:00:00Z"),
    device = "phone-1",
    minutes = minutes,
)

internal fun written(vararg actions: String) = MinutesState.Written(
    WrittenMinutes("", emptyList(), actions.map { MinutesAction(it, owner = null, due = null, done = false) }),
)
