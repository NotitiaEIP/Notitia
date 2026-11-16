package com.example.notitia.ui.components

// The same placeholders as the resources: %1$s or %1$d.
private val PLACEHOLDER = Regex("""%(\d+)\$[ds]""")

// Fills a resource read once in composition: the texts handed to the share sheet are built outside it, and
// tested without Compose.
fun String.filled(vararg args: String): String =
    PLACEHOLDER.replace(this) { args[it.groupValues[1].toInt() - 1] }

// For a line that goes on after the text: a full stop the model left would sit in the middle of it.
fun String.withoutFinalStop(): String = trim().trimEnd('.', ' ')
