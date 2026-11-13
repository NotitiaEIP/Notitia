package com.example.notitia.ui.actions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.meeting_day_month
import notitia.shared.generated.resources.months_short
import notitia.shared.generated.resources.nudge_hello
import notitia.shared.generated.resources.nudge_line
import notitia.shared.generated.resources.nudge_line_due
import notitia.shared.generated.resources.nudge_many
import notitia.shared.generated.resources.nudge_meeting
import notitia.shared.generated.resources.nudge_one
import notitia.shared.generated.resources.nudge_thanks
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

// What a nudge reminds someone of: their open actions, under the meeting each was agreed in.
data class Nudge(val name: String, val meetings: List<NudgeMeeting>)

data class NudgeMeeting(val title: String, val day: LocalDate, val lines: List<NudgeLine>)

data class NudgeLine(val text: String, val due: LocalDate?)

// Only someone named can be nudged, and only about what is still to do.
fun nudgeFor(tracked: TrackedAction, timeZone: TimeZone): Nudge? {
    val owner = tracked.action.owner ?: return null
    if (tracked.action.done) return null
    return nudge(owner, listOf(tracked), timeZone)
}

// A just-ticked action lingers in open a moment: it is done, so it is left out.
fun nudgeFor(person: PersonActions, timeZone: TimeZone): Nudge? {
    val name = person.name ?: return null
    val open = person.open.filter { !it.action.done }
    if (open.isEmpty()) return null
    return nudge(name, open, timeZone)
}

// The meeting with the most pressing action comes first. The actions keep the order they were given in.
private fun nudge(name: String, actions: List<TrackedAction>, timeZone: TimeZone): Nudge {
    val meetings = actions
        .groupBy { it.meeting.id }
        .values
        .sortedWith(
            compareBy<List<TrackedAction>> { it.firstDue() == null }
                .thenBy { it.firstDue() }
                .thenBy { it.first().meeting.startedAt },
        )
        .map { inMeeting ->
            val meeting = inMeeting.first().meeting
            NudgeMeeting(
                title = meeting.title,
                day = meeting.startedAt.toLocalDateTime(timeZone).date,
                lines = inMeeting.map { NudgeLine(it.action.text, it.action.due) },
            )
        }
    return Nudge(name, meetings)
}

private fun List<TrackedAction>.firstDue(): LocalDate? = mapNotNull { it.action.due }.minOrNull()

// The words of the message, as written in the resources with their %1$s, and how a date reads.
class NudgeWords(
    val hello: String,
    val one: String,
    val many: String,
    val meeting: String,
    val line: String,
    val lineDue: String,
    val thanks: String,
    val date: (LocalDate) -> String,
)

@Composable
fun rememberNudgeWords(): NudgeWords {
    val hello = stringResource(Res.string.nudge_hello)
    val one = stringResource(Res.string.nudge_one)
    val many = stringResource(Res.string.nudge_many)
    val meeting = stringResource(Res.string.nudge_meeting)
    val line = stringResource(Res.string.nudge_line)
    val lineDue = stringResource(Res.string.nudge_line_due)
    val thanks = stringResource(Res.string.nudge_thanks)
    val dayMonth = stringResource(Res.string.meeting_day_month)
    val months = stringArrayResource(Res.array.months_short)
    return remember(hello, one, many, meeting, line, lineDue, thanks, dayMonth, months) {
        NudgeWords(hello, one, many, meeting, line, lineDue, thanks) { date ->
            dayMonth.filled(date.day.toString(), months[date.month.ordinal])
        }
    }
}

// One meeting reads as a single reminder; several get a heading each.
fun nudgeText(nudge: Nudge, words: NudgeWords): String = buildString {
    appendLine(words.hello.filled(nudge.name))
    appendLine()
    val single = nudge.meetings.singleOrNull()
    if (single != null) {
        appendLine(words.one.filled(single.title, words.date(single.day)))
        single.lines.forEach { appendLine(it.words(words)) }
    } else {
        appendLine(words.many)
        nudge.meetings.forEach { meeting ->
            appendLine()
            appendLine(words.meeting.filled(meeting.title, words.date(meeting.day)))
            meeting.lines.forEach { appendLine(it.words(words)) }
        }
    }
    appendLine()
    append(words.thanks)
}

// The line adds its own comma after the text: a full stop the model left would sit in the middle of it.
private fun NudgeLine.words(words: NudgeWords): String {
    val text = text.trim().trimEnd('.', ' ')
    return due?.let { words.lineDue.filled(text, words.date(it)) } ?: words.line.filled(text)
}

// The same placeholders as the resources: %1$s or %1$d.
private val PLACEHOLDER = Regex("""%(\d+)\$[ds]""")

private fun String.filled(vararg args: String): String =
    PLACEHOLDER.replace(this) { args[it.groupValues[1].toInt() - 1] }
