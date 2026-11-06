package com.example.notitia.ai

// Some open models write their reasoning in the reply.
private val reasoning = Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE)

internal fun withoutReasoning(reply: String): String = reply.replace(reasoning, "")
