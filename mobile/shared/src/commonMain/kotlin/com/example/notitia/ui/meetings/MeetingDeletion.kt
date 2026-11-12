package com.example.notitia.ui.meetings

import androidx.compose.runtime.Composable
import com.example.notitia.ui.components.Destruction
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.meeting_delete
import notitia.shared.generated.resources.meeting_delete_confirm
import notitia.shared.generated.resources.meeting_delete_message
import notitia.shared.generated.resources.meeting_delete_title
import notitia.shared.generated.resources.meetings_delete_confirm
import notitia.shared.generated.resources.meetings_delete_message
import notitia.shared.generated.resources.meetings_delete_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// The same words from the minutes' menu, a card's menu and the selection's bin. One meeting is named, as in Files;
// several are counted.
@Composable
fun meetingDeletion(title: String): Destruction = Destruction(
    title = stringResource(Res.string.meeting_delete_title, title),
    message = stringResource(Res.string.meeting_delete_message),
    confirm = stringResource(Res.string.meeting_delete_confirm),
    shortConfirm = stringResource(Res.string.meeting_delete),
)

@Composable
fun meetingsDeletion(count: Int): Destruction = Destruction(
    title = pluralStringResource(Res.plurals.meetings_delete_title, count, count),
    message = pluralStringResource(Res.plurals.meetings_delete_message, count),
    confirm = pluralStringResource(Res.plurals.meetings_delete_confirm, count, count),
    shortConfirm = stringResource(Res.string.meeting_delete),
)
