package com.example.notitia.ui.navigation

import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.actions_title
import notitia.shared.generated.resources.meetings_title
import notitia.shared.generated.resources.search_title
import org.jetbrains.compose.resources.StringResource

// The three top-level screens, in tab bar order. Their icons are drawn by TabIcon.
enum class Tab(val title: StringResource) {
    Meetings(Res.string.meetings_title),
    Actions(Res.string.actions_title),
    Search(Res.string.search_title),
}
