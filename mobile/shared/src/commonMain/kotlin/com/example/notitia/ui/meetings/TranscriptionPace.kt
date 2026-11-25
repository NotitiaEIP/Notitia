package com.example.notitia.ui.meetings

import com.example.notitia.meeting.Meeting
import kotlin.time.Duration
import kotlin.time.Instant

// How fast the core hears each meeting, from what it told: the time left at the pace a meeting has gone since it
// started moving. The core cannot say it, as its speed is the engine's. Nothing is guessed before a meeting moves, so
// one that waits for its turn, or for a model, shows no time; only the meetings being heard are kept.
class TranscriptionPace private constructor(private val marks: Map<String, Mark>) {
    constructor() : this(emptyMap())

    // since: the last time it was seen still, before it first moved. moved: the last time it was seen further on.
    private data class Mark(val since: Point, val moved: Point?)

    private data class Point(val at: Instant, val heard: Duration)

    fun next(meetings: List<Meeting>, now: Instant): TranscriptionPace = TranscriptionPace(
        meetings.mapNotNull { meeting ->
            val heard = meeting.transcribed ?: return@mapNotNull null
            meeting.id to seen(marks[meeting.id], Point(now, heard))
        }.toMap(),
    )

    fun remaining(meeting: Meeting): Duration? {
        val heard = meeting.transcribed ?: return null
        val mark = marks[meeting.id] ?: return null
        val moved = mark.moved ?: return null
        val took = moved.at - mark.since.at
        val went = moved.heard - mark.since.heard
        if (took <= Duration.ZERO || went <= Duration.ZERO) return null
        return ((meeting.duration - heard) * (took / went)).coerceAtLeast(Duration.ZERO)
    }

    // A meeting heard again from its start begins a new mark: the pace of a former hearing says nothing.
    private fun seen(mark: Mark?, now: Point): Mark {
        if (mark == null) return Mark(since = now, moved = null)
        val last = mark.moved ?: mark.since
        return when {
            now.heard < last.heard -> Mark(since = now, moved = null)
            now.heard > last.heard -> mark.copy(moved = now)
            mark.moved == null -> Mark(since = now, moved = null)
            else -> mark
        }
    }
}
