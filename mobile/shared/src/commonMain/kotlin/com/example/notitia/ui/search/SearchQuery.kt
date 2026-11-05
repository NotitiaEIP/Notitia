package com.example.notitia.ui.search

import com.example.notitia.ui.meetings.MeetingListItem
import kotlin.time.Duration

// A word search is instant and stays out of the model; a question goes through the model.
enum class QueryKind { Words, Question }

data class Passage(
    val meetingId: String,
    val at: Duration,
    val speaker: Int,
    // Named by the user in this meeting only: no voiceprint links speakers across meetings (product rule 4).
    val name: String,
    val text: String,
)

data class Answer(val parts: List<AnswerPart>, val sources: List<Passage>, val followUps: List<String>)

// source indexes Answer.sources, and is null for the text after the last citation.
data class AnswerPart(val text: String, val source: Int?)

private const val QUESTION_MIN_WORDS = 4

private val questionWords = setOf(
    "qui", "que", "quoi", "quand", "ou", "comment", "pourquoi", "combien", "quel", "quelle", "quels", "quelles",
)

private val spaces = Regex("""[\s  ]+""")

fun queryKind(text: String): QueryKind {
    val query = text.trim()
    val words = words(fold(query))
    val first = words.firstOrNull() ?: return QueryKind.Words
    val asks = query.endsWith('?') || first in questionWords || first.startsWith("est-ce") || first.startsWith("qu'")
    return if (asks || words.size >= QUESTION_MIN_WORDS) QueryKind.Question else QueryKind.Words
}

// One character for one, so what is found in the folded text is highlighted at the same place in the original.
fun fold(text: String): String = buildString(text.length) {
    text.forEach { append(foldChar(it.lowercaseChar())) }
}

// Keyboards type a straight apostrophe, the samples and the transcription a curly one.
private fun foldChar(char: Char): Char = when (char) {
    'à', 'â', 'ä' -> 'a'
    'é', 'è', 'ê', 'ë' -> 'e'
    'î', 'ï' -> 'i'
    'ô', 'ö' -> 'o'
    'ù', 'û', 'ü' -> 'u'
    'ç' -> 'c'
    'ÿ' -> 'y'
    '’' -> '\''
    else -> char
}

private fun words(text: String): List<String> = text.split(spaces).filter { it.isNotEmpty() }

fun findPassages(query: String, passages: List<Passage>): List<Passage> {
    val words = words(fold(query))
    if (words.isEmpty()) return emptyList()
    return passages.filter { passage ->
        val text = fold(passage.text)
        words.all { it in text }
    }
}

// The most recent meeting first; inside a meeting, the passages in the order they were said.
fun groupByMeeting(
    passages: List<Passage>,
    meetings: List<MeetingListItem>,
): List<Pair<MeetingListItem, List<Passage>>> =
    meetings
        .sortedByDescending { it.startedAt }
        .mapNotNull { meeting ->
            passages
                .filter { it.meetingId == meeting.id }
                .sortedBy { it.at }
                .takeIf { it.isNotEmpty() }
                ?.let { meeting to it }
        }

fun highlights(text: String, query: String): List<IntRange> {
    val folded = fold(text)
    return words(fold(query))
        .flatMap { occurrences(folded, it) }
        .sortedBy { it.first }
        .fold(mutableListOf()) { merged, range ->
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
            merged
        }
}

private fun occurrences(text: String, word: String): List<IntRange> = buildList {
    var start = text.indexOf(word)
    while (start >= 0) {
        add(start until start + word.length)
        start = text.indexOf(word, start + word.length)
    }
}

// "Qu’a-t-on décidé pour la démo ?" and "qu'a-t-on decide pour la demo" ask the same thing.
fun sameQuestion(a: String, b: String): Boolean = questionKey(a) == questionKey(b)

internal fun questionKey(text: String): String = fold(text).trim().removeSuffix("?").trim()
