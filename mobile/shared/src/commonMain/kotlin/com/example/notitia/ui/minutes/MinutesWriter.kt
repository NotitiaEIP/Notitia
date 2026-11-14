package com.example.notitia.ui.minutes

import com.example.notitia.ai.LanguageModel
import com.example.notitia.ai.withoutReasoning
import com.example.notitia.meeting.ActionSource
import com.example.notitia.meeting.MinutesAction
import com.example.notitia.meeting.TranscriptLine
import com.example.notitia.meeting.WrittenMinutes
import com.example.notitia.ui.recording.timerText
import kotlinx.datetime.LocalDate

private const val SUMMARY = "SUMMARY:"
private const val DECISION = "DECISION:"
private const val ACTION = "ACTION:"
private const val NONE = "-"
private const val ACTION_FIELDS = 3

// One line per item rather than JSON: the open models break JSON more often than lines.
private val MINUTES_INSTRUCTIONS = """
    You write the minutes of a meeting, in French, from its transcript. Each transcript line starts with its time.
    Keep only what was actually said: never make up a decision, a task, a name or a date.
    The speakers are not identified: name the person in charge of a task only if the transcript names them.
    Reply only with these lines, and nothing else:
    $SUMMARY two or three sentences on what the meeting was about.
    $DECISION one line per decision taken. If nothing was decided, write exactly: $DECISION $NONE
    $ACTION task | first name of the person in charge, or $NONE | due date as YYYY-MM-DD, or $NONE | time of the transcript line where it was agreed, as written at its start, or $NONE
    One $ACTION line per task someone agreed to do. If there is none, write exactly: $ACTION $NONE
""".trimIndent()

interface MinutesWriter {
    // day is when the meeting took place, so "vendredi" becomes a date.
    suspend fun write(lines: List<TranscriptLine>, day: LocalDate): WrittenMinutes
}

class LanguageModelMinutesWriter(private val model: LanguageModel) : MinutesWriter {
    override suspend fun write(lines: List<TranscriptLine>, day: LocalDate): WrittenMinutes =
        parseMinutes(model.complete(MINUTES_INSTRUCTIONS, minutesPrompt(lines, day)), lines)
}

internal fun minutesPrompt(lines: List<TranscriptLine>, day: LocalDate): String = buildString {
    appendLine("Meeting of $day (${day.dayOfWeek.name.lowercase()}).")
    appendLine()
    lines.forEach { line -> line.text?.let { appendLine("[${timerText(line.at)}] $it") } }
}

// What the model writes for an empty list despite the instructions: "Aucune décision n’a été prise."
// Only the whole sentence counts, so "Aucune action marketing avant janvier" stays a decision.
private val nothingSaid = Regex(
    """^(-|none|n/?a|néant|rien|aucune?|pas de|pas d['’]\s*\p{L}+)\.?$|""" +
        """^(aucune?|pas de|pas d['’])\s*(décision|action|tâche)s?(\s+\p{L}+)?""" +
        """(\s+(n['’](a|ont|est|sont)|ne\s|prises?|retenues?|identifiées?|définies?|attribuées?|à signaler)\b.*)?\.?$""",
    RegexOption.IGNORE_CASE,
)

// Lines the model adds around the expected ones are dropped, and so is a date it wrote in another format.
// lines is the transcript the model read: an action's time points to one of them.
internal fun parseMinutes(reply: String, lines: List<TranscriptLine> = emptyList()): WrittenMinutes {
    val summary = mutableListOf<String>()
    val decisions = mutableListOf<String>()
    val actions = mutableListOf<MinutesAction>()
    withoutReasoning(reply).lines()
        .map { it.trim().trimStart('-', '*', '•', ' ') }
        .forEach { line ->
            when {
                line.startsWith(SUMMARY, ignoreCase = true) -> summary += line.substring(SUMMARY.length).trim()
                line.startsWith(DECISION, ignoreCase = true) -> decisions += line.substring(DECISION.length).trim()
                line.startsWith(ACTION, ignoreCase = true) -> parseAction(line.substring(ACTION.length), lines)?.let { actions += it }
            }
        }
    return WrittenMinutes(
        summary = summary.filter { it.isNotEmpty() && it != NONE }.joinToString(" "),
        decisions = decisions.filter { it.isNotEmpty() && !nothingSaid.matches(it) },
        actions = actions.filterNot { nothingSaid.matches(it.text) },
    )
}

// A task may hold a bar of its own: the three fields asked of the model are always the last ones.
private fun parseAction(line: String, lines: List<TranscriptLine>): MinutesAction? {
    val parts = line.split('|').map { it.trim() }
    val textParts = (parts.size - ACTION_FIELDS).coerceAtLeast(1)
    val text = parts.take(textParts).joinToString(" | ").takeIf { it.isNotEmpty() } ?: return null
    val fields = parts.drop(textParts)
    return MinutesAction(
        text = text,
        owner = fields.getOrNull(0)?.takeUnless { it.isEmpty() || it == NONE },
        due = fields.getOrNull(1)?.let(::parseDate),
        done = false,
        source = fields.getOrNull(2)?.let { sourceAt(it, lines) },
    )
}

// The quote is copied from the transcript, so the model can point to a sentence but never make one up.
// A time that matches no line gives no source.
private fun sourceAt(time: String, lines: List<TranscriptLine>): ActionSource? {
    val wanted = time.trim('[', ']', ' ')
    return lines.firstNotNullOfOrNull { line ->
        line.text?.takeIf { timerText(line.at) == wanted }?.let { ActionSource(quote = it, speaker = null, at = line.at) }
    }
}

private fun parseDate(text: String): LocalDate? =
    try {
        LocalDate.parse(text)
    } catch (_: IllegalArgumentException) {
        null
    }
