package com.example.notitia.ui.meetings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.example.notitia.ui.theme.SelectionStyle
import com.example.notitia.ui.theme.selectionStyle

// The meetings chosen in the list, and the deletion waiting for its confirmation; chosen is null while the list is
// not choosing. Held by App, so the iPhone's toolbar, which takes the tab bar's place, reads the same choice.
@Stable
class MeetingSelection(
    private val closesWhenEmpty: Boolean = selectionStyle == SelectionStyle.ContextualBar,
    chosen: Set<String>? = null,
) {
    var chosen: Set<String>? by mutableStateOf(chosen)
        private set

    val active: Boolean get() = chosen != null

    // Set by the bin, wherever it sits (Android's top bar, the iPhone's toolbar), or by a card's menu: the list then
    // asks before deleting.
    var deleteRequest: DeleteRequest? by mutableStateOf(null)
        private set

    fun start(id: String? = null) {
        chosen = setOfNotNull(id)
    }

    fun toggle(id: String) {
        val now = chosen ?: return
        settle(if (id in now) now - id else now + id)
    }

    // With everything chosen, the same button lets go of everything, as « Tout désélectionner » in Mail.
    fun toggleAll(ids: Collection<String>) {
        if (!active) return
        settle(if (allChosen(ids)) emptySet() else ids.toSet())
    }

    fun allChosen(ids: Collection<String>): Boolean = ids.isNotEmpty() && chosen?.containsAll(ids) == true

    fun askToDelete(anchor: Rect?) {
        chosen?.takeIf { it.isNotEmpty() }?.let { deleteRequest = DeleteRequest(it, anchor) }
    }

    fun askToDelete(id: String, anchor: Rect?) {
        deleteRequest = DeleteRequest(setOf(id), anchor)
    }

    fun cancelDelete() {
        deleteRequest = null
    }

    fun end() {
        chosen = null
        deleteRequest = null
    }

    private fun settle(next: Set<String>) {
        chosen = if (next.isEmpty() && closesWhenEmpty) null else next
    }

    companion object {
        // An ArrayList, which an Android Bundle keeps; nothing is saved while the list is not choosing.
        fun saver(closesWhenEmpty: Boolean = selectionStyle == SelectionStyle.ContextualBar) =
            Saver<MeetingSelection, ArrayList<String>>(
                save = { selection -> selection.chosen?.let { ArrayList(it) } },
                restore = { MeetingSelection(closesWhenEmpty, it.toSet()) },
            )
    }
}

// anchor: the bin or the card in window pixels, which the iPhone's confirmation grows out of.
data class DeleteRequest(val ids: Set<String>, val anchor: Rect?)
