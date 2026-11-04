package com.example.notitia.ui.search

import com.example.notitia.ai.LanguageModel
import kotlin.math.sqrt

private const val MAX_SOURCES = 4
private const val MAX_FOLLOW_UPS = 2
private const val FOLLOW_UP = "FOLLOW-UP:"

private val ANSWER_INSTRUCTIONS = """
    You answer questions about meetings, in French, using only the numbered passages you are given.
    Write one or two short sentences. Right after the words a passage backs, put its number in square brackets: [1].
    If the passages do not answer the question, reply only with NOTHING.
    After the answer, add up to $MAX_FOLLOW_UPS short questions in French that the passages could also answer, one per line, each starting with $FOLLOW_UP
""".trimIndent()

// Some open models write their reasoning in the reply.
private val reasoning = Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE)
private val citation = Regex("""\[\s*\d+(?:\s*,\s*\d+)*\s*]""")
private val number = Regex("""\d+""")

class RagAnswerEngine(
    private val model: LanguageModel,
    private val passages: List<Passage> = samplePassages,
) : AnswerEngine {

    override suspend fun findSources(question: String): List<Passage> {
        val vectors = model.embed(listOf(question) + passages.map { it.text })
        return closestPassages(vectors.first(), passages.zip(vectors.drop(1)))
    }

    override suspend fun writeAnswer(question: String, sources: List<Passage>): Answer? =
        parseAnswer(model.complete(ANSWER_INSTRUCTIONS, answerPrompt(question, sources)), sources)
}

internal fun closestPassages(question: List<Double>, passages: List<Pair<Passage, List<Double>>>): List<Passage> =
    passages
        .sortedByDescending { (_, vector) -> cosine(question, vector) }
        .take(MAX_SOURCES)
        .map { (passage, _) -> passage }

private fun cosine(a: List<Double>, b: List<Double>): Double {
    val norm = sqrt(a.sumOf { it * it }) * sqrt(b.sumOf { it * it })
    return if (norm == 0.0) 0.0 else a.zip(b).sumOf { (x, y) -> x * y } / norm
}

internal fun answerPrompt(question: String, sources: List<Passage>): String = buildString {
    sources.forEachIndexed { index, source -> appendLine("[${index + 1}] ${source.name} : ${source.text}") }
    appendLine()
    append("Question : $question")
}

// An answer that cites nothing is not trusted, which also covers the NOTHING reply. A number the model made up
// loses its citation; the sources are renumbered in the order they are cited.
internal fun parseAnswer(reply: String, sources: List<Passage>): Answer? {
    val lines = reply.replace(reasoning, "").lines().map { it.trim() }.filter { it.isNotEmpty() }
    val (followUps, body) = lines.partition { it.startsWith(FOLLOW_UP, ignoreCase = true) }
    val text = body.joinToString(" ")

    val cited = mutableListOf<Passage>()
    val parts = mutableListOf<AnswerPart>()
    var pending = ""
    var position = 0
    citation.findAll(text).forEach { match ->
        pending += text.substring(position, match.range.first)
        position = match.range.last + 1
        val backing = number.findAll(match.value).mapNotNull { sources.getOrNull(it.value.toInt() - 1) }.toList()
        if (backing.isEmpty()) pending = pending.trimEnd()
        backing.forEach { source ->
            if (source !in cited) cited += source
            parts += AnswerPart(pending.trimEnd(), source = cited.indexOf(source))
            pending = ""
        }
    }
    if (cited.isEmpty()) return null

    val rest = pending + text.substring(position)
    if (rest.isNotEmpty()) parts += AnswerPart(rest, source = null)
    return Answer(
        parts = parts,
        sources = cited,
        followUps = followUps.map { it.substring(FOLLOW_UP.length).trim() }.filter { it.isNotEmpty() }.take(MAX_FOLLOW_UPS),
    )
}
