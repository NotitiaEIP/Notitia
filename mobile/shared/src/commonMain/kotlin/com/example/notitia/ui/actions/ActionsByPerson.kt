package com.example.notitia.ui.actions

// The actions of one owner across every meeting, as the core places them (PlacedPerson), each with its text. key and
// name are null for the actions nobody owns. done and total feed the ring, open what the card unfolds on, late what
// the card says in red.
data class PersonActions(
    val key: String?,
    val name: String?,
    val open: List<TrackedAction>,
    val late: Int,
    val done: Int,
    val total: Int,
)

// Owners are told apart by the first name as written, never by the voice (product rule 4): "clément", "Clément"
// and "Clement" are one person, whatever speaker said it. The core keys the board the same way (ADR 0017): the pairs
// of core/tests/fixtures/bridge/board.json hold both to it, until the core gives every name its key and colour.
fun personKey(name: String): String =
    name.trim().split(Regex("\\s+")).joinToString(" ").lowercase().map { ACCENTS[it] ?: it.toString() }.joinToString("")

// A first name typed in the sheet takes the spelling the meeting already has for it: "clement" becomes "Clément",
// with Clément's colour, in every view, not a stranger next to him.
fun knownSpelling(name: String, known: List<String>): String =
    known.firstOrNull { personKey(it) == personKey(name) } ?: name

private val ACCENTS: Map<Char, String> = buildMap {
    "àâäá".forEach { put(it, "a") }
    "ç".forEach { put(it, "c") }
    "éèêë".forEach { put(it, "e") }
    "îïí".forEach { put(it, "i") }
    "ôöó".forEach { put(it, "o") }
    "ùûüú".forEach { put(it, "u") }
    "ÿ".forEach { put(it, "y") }
    "ñ".forEach { put(it, "n") }
    put('œ', "oe")
    put('æ', "ae")
}

// While the list is on screen, the cards keep the order they first had, as the lists of Reminders: ticking the
// actions of one person one after the other must not send their card away under the next tap. Someone new comes in
// at the end, before the actions nobody owns. The order by late comes back the next time the list opens.
fun List<PersonActions>.keptInOrder(order: List<String?>): List<PersonActions> {
    val rank = order.withIndex().associate { (index, key) -> key to index }
    val (owned, unowned) = partition { it.key != null }
    val (known, new) = owned.partition { it.key in rank }
    return known.sortedBy { rank.getValue(it.key) } + new + unowned
}
