package com.example.notitia.meeting

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Where a due date stands on the day the board was made for, the most urgent first. The core decides, from the day
// the app gives it (docs/adr/0017-tableau-des-actions.md).
@Serializable
enum class DueGroup {
    @SerialName("late")
    Late,

    @SerialName("today")
    Today,

    @SerialName("tomorrow")
    Tomorrow,

    // Within the week.
    @SerialName("nextDays")
    NextDays,

    @SerialName("later")
    Later,
}

// An action by reference, with what the day decides of it: its text, owner and tick are those of its meeting. group is
// null without a due date, daysLate only for an action late and not done.
@Serializable
data class Placed(
    val meeting: String,
    val action: String,
    val group: DueGroup? = null,
    val daysLate: Int? = null,
) {
    val id: ActionId get() = ActionId(meeting, action)
}

@Serializable
data class Grouped(val group: DueGroup, val actions: List<Placed>)

// The actions of one owner across every meeting, what is still to do first. No key and no name for the actions nobody
// owns. done and total feed the ring, late what the card says in red.
@Serializable
data class PlacedPerson(
    val key: String? = null,
    val name: String? = null,
    val open: List<Placed>,
    val late: Int,
    val done: Int,
    val total: Int,
)

// The Actions tab as the core made it for a day, both ways to read it, and what is late in each meeting. meetings are
// those held when it came: a screen reads the actions there, never in a later list, so it never shows the text of one
// instant at the place of another.
data class ActionBoard(
    val today: LocalDate,
    // Lacking an owner or a due date: they cannot be followed yet.
    val toComplete: List<Placed>,
    // By due date, the most urgent first, no group empty.
    val groups: List<Grouped>,
    val done: List<Placed>,
    val open: Int,
    val late: Int,
    // The most late first, then the busiest; the actions nobody owns last.
    val people: List<PlacedPerson>,
    // Only the meetings with an action late and not done.
    val lateByMeeting: Map<String, Int>,
    val meetings: List<Meeting>,
) {
    companion object {
        // What there is to place without a single meeting.
        fun empty(today: LocalDate) =
            ActionBoard(today, emptyList(), emptyList(), emptyList(), 0, 0, emptyList(), emptyMap(), emptyList())
    }
}
