package com.example.notitia.meeting

import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

// What the screens ask of the meetings. The Kotlin repository holds it today, the Rust core will after T-57: each
// change is a value, never a function, so it can cross the bridge.
interface Meetings {
    // null until the files are read, so the list never flashes empty at launch.
    val meetings: StateFlow<List<Meeting>?>

    fun rename(id: String, title: String)

    fun delete(ids: Set<String>)

    fun setDone(id: ActionId, done: Boolean)

    fun setOwner(id: ActionId, owner: String?)

    fun setDue(id: ActionId, due: LocalDate?)

    fun setReminder(id: ActionId, on: Boolean)
}
