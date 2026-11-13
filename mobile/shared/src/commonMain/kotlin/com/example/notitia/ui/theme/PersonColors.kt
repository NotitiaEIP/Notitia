package com.example.notitia.ui.theme

import com.example.notitia.meeting.Meeting
import com.example.notitia.meeting.MinutesState
import com.example.notitia.ui.actions.personKey

// As many as the speaker palette of each theme holds.
const val PERSON_COLORS = 9

// The colour of each person, given in the order their first name first appears in the meetings, oldest first: the
// first nine people have nine different colours, the same in every meeting and every tab. Names, never voices
// (product rule 4), and nothing is stored: the colours are worked out again from the meetings.
class PersonColors(private val slots: Map<String, Int> = emptyMap()) {

    // A name no meeting holds yet, as in previews, still gets a colour of its own.
    fun of(name: String): Int = slots[personKey(name)] ?: personKey(name).hashCode().mod(PERSON_COLORS)

    // Inside one meeting, in the order people first spoke: each keeps their colour unless someone before took it,
    // then takes the next free one, in this meeting only, so no two people of a meeting share a colour.
    fun inMeeting(names: List<String>): PersonColors {
        val taken = mutableSetOf<Int>()
        val inMeeting = names.distinctBy(::personKey).associate { name ->
            var slot = of(name)
            while (slot in taken && taken.size < PERSON_COLORS) slot = (slot + 1) % PERSON_COLORS
            taken += slot
            personKey(name) to slot
        }
        return PersonColors(slots + inMeeting)
    }
}

// Speakers in the order they spoke, then the owners the minutes name.
fun personColors(meetings: List<Meeting>): PersonColors {
    val slots = mutableMapOf<String, Int>()
    meetings.sortedBy { it.startedAt }.forEach { meeting ->
        val owners = (meeting.minutes as? MinutesState.Written)?.minutes?.actions?.mapNotNull { it.owner }.orEmpty()
        (meeting.speakers + owners).forEach { name -> slots.getOrPut(personKey(name)) { slots.size % PERSON_COLORS } }
    }
    return PersonColors(slots)
}
