package com.example.notitia.ui.actions

import com.example.notitia.meeting.ActionId
import kotlinx.datetime.LocalDate

// The actions of one owner across every meeting. key and name are null for the actions nobody owns. name comes from
// the most recent meeting: the latest spelling the user wrote or kept. done and total feed the ring, open what the
// card unfolds on, late what the card says in red.
data class PersonActions(
    val key: String?,
    val name: String?,
    val open: List<TrackedAction>,
    val late: Int,
    val done: Int,
    val total: Int,
)

// Owners are told apart by the first name as written, never by the voice (product rule 4): "clément", "Clément"
// and "Clement" are one person, whatever speaker said it.
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

// The people with the most late, then the most open, come first: they are the ones to talk to. The actions nobody
// owns close the list. A lingering action was just ticked: the ring counts it done at once, but it stays in open and
// late a moment, so no card moves under the finger, as in the list by due date.
fun actionsByPerson(
    actions: List<TrackedAction>,
    today: LocalDate,
    lingering: Set<ActionId> = emptySet(),
): List<PersonActions> {
    val (owned, unowned) = actions.partition { it.action.owner != null }
    val people = owned
        .groupBy { personKey(checkNotNull(it.action.owner)) }
        .map { (key, ofPerson) -> personActions(key, ofPerson, today, lingering) }
        .sortedWith(compareByDescending<PersonActions> { it.late }.thenByDescending { it.open.size }.thenBy { it.key })
    return if (unowned.isEmpty()) people else people + personActions(null, unowned, today, lingering)
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

private fun personActions(
    key: String?,
    actions: List<TrackedAction>,
    today: LocalDate,
    lingering: Set<ActionId>,
): PersonActions {
    val latest = actions.maxBy { it.meeting.startedAt }
    val open = actions
        .filter { !it.action.done || it.id in lingering }
        .sortedWith(
            compareBy<TrackedAction> { it.action.due == null }
                .thenBy { it.action.due }
                .thenBy { it.meeting.startedAt },
        )
    return PersonActions(
        key = key,
        name = latest.action.owner,
        open = open,
        late = open.count { it.action.due?.let { due -> dueGroup(due, today) } == DueGroup.Late },
        done = actions.count { it.action.done },
        total = actions.size,
    )
}
