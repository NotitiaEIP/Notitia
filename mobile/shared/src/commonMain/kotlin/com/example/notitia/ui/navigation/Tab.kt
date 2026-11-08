package com.example.notitia.ui.navigation

import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.actions_back_description
import notitia.shared.generated.resources.actions_title
import notitia.shared.generated.resources.meetings_title
import notitia.shared.generated.resources.minutes_back_description
import notitia.shared.generated.resources.search_back_description
import notitia.shared.generated.resources.search_title
import org.jetbrains.compose.resources.StringResource

// A meeting opens over a tab, and its back button names that tab.
enum class Tab(val title: StringResource, val backDescription: StringResource) {
    Meetings(Res.string.meetings_title, Res.string.minutes_back_description),
    Actions(Res.string.actions_title, Res.string.actions_back_description),
    Search(Res.string.search_title, Res.string.search_back_description),
}
