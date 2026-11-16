package com.example.notitia.ui.minutes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.ui.components.filled
import com.example.notitia.ui.components.withoutFinalStop
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.minutes_actions
import notitia.shared.generated.resources.minutes_decisions
import notitia.shared.generated.resources.minutes_share_action_due
import notitia.shared.generated.resources.minutes_share_action_owner
import notitia.shared.generated.resources.minutes_share_action_owner_due
import notitia.shared.generated.resources.minutes_share_date
import notitia.shared.generated.resources.minutes_share_line
import notitia.shared.generated.resources.minutes_summary
import notitia.shared.generated.resources.months_short
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

// The words of the shared minutes, as written in the resources with their %1$s, and how a date reads.
class MinutesShareWords(
    val summary: String,
    val decisions: String,
    val actions: String,
    val line: String,
    val actionOwner: String,
    val actionDue: String,
    val actionOwnerDue: String,
    val date: (LocalDate) -> String,
)

@Composable
fun rememberMinutesShareWords(): MinutesShareWords {
    val summary = stringResource(Res.string.minutes_summary)
    val decisions = stringResource(Res.string.minutes_decisions)
    val actions = stringResource(Res.string.minutes_actions)
    val line = stringResource(Res.string.minutes_share_line)
    val actionOwner = stringResource(Res.string.minutes_share_action_owner)
    val actionDue = stringResource(Res.string.minutes_share_action_due)
    val actionOwnerDue = stringResource(Res.string.minutes_share_action_owner_due)
    val dateFormat = stringResource(Res.string.minutes_share_date)
    val months = stringArrayResource(Res.array.months_short)
    return remember(summary, decisions, actions, line, actionOwner, actionDue, actionOwnerDue, dateFormat, months) {
        MinutesShareWords(summary, decisions, actions, line, actionOwner, actionDue, actionOwnerDue) { date ->
            dateFormat.filled(date.day.toString(), months[date.month.ordinal], date.year.toString())
        }
    }
}

// Read by someone who was not there: what it was about comes before what was decided, and an empty part is left
// out rather than announced. The date carries its year, since the text outlives the meeting.
fun minutesShareText(minutes: Minutes, words: MinutesShareWords, timeZone: TimeZone): String {
    val meeting = minutes.meeting
    val parts = buildList {
        add(listOf(meeting.title, words.date(meeting.startedAt.toLocalDateTime(timeZone).date)))
        if (minutes.summary.isNotBlank()) add(listOf(words.summary, minutes.summary.trim()))
        if (minutes.decisions.isNotEmpty()) {
            add(listOf(words.decisions) + minutes.decisions.map { words.line.filled(it.trim()) })
        }
        if (minutes.actions.isNotEmpty()) {
            add(listOf(words.actions) + minutes.actions.map { it.words(words) })
        }
    }
    return parts.joinToString("\n\n") { it.joinToString("\n") }
}

private fun MinutesAction.words(words: MinutesShareWords): String {
    val text = text.withoutFinalStop()
    val due = due?.let(words.date)
    return when {
        owner != null && due != null -> words.actionOwnerDue.filled(text, owner, due)
        owner != null -> words.actionOwner.filled(text, owner)
        due != null -> words.actionDue.filled(text, due)
        else -> words.line.filled(text)
    }
}
