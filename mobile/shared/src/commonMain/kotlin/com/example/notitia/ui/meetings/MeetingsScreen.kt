package com.example.notitia.ui.meetings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.notitia.ui.components.MeetingCard
import com.example.notitia.ui.components.RecordButton
import com.example.notitia.ui.components.EmptyState
import com.example.notitia.ui.components.GroupTitle
import com.example.notitia.ui.components.HeaderAction
import com.example.notitia.ui.components.ScreenHeader
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.lock
import notitia.shared.generated.resources.meetings_empty
import notitia.shared.generated.resources.meetings_on_device
import notitia.shared.generated.resources.meetings_on_this_phone
import notitia.shared.generated.resources.meetings_title
import notitia.shared.generated.resources.settings
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun MeetingsScreen(
    meetings: List<MeetingListItem>,
    recording: Boolean,
    onOpen: (MeetingListItem) -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        if (meetings.isEmpty()) {
            EmptyMeetings(onSettings)
        } else {
            MeetingList(meetings, onOpen, onSettings)
        }
        // While a recording runs, the mini bar stands in for the button: a second one could not start anything.
        if (!recording) {
            RecordButton(
                onClick = onRecord,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = NotitiaTheme.spacing.xl),
            )
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    ScreenHeader(title = stringResource(Res.string.meetings_title)) {
        HeaderAction(
            icon = painterResource(Res.drawable.settings),
            description = stringResource(Res.string.settings),
            onClick = onSettings,
        )
    }
}

@Composable
private fun OnThisPhone(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.lock),
            contentDescription = null,
            tint = NotitiaTheme.colors.accentContent,
            modifier = Modifier.size(NotitiaTheme.sizes.iconSmall),
        )
        Text(
            text = pluralStringResource(Res.plurals.meetings_on_this_phone, count, count),
            style = NotitiaTheme.typography.caption,
            color = NotitiaTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun EmptyMeetings(onSettings: () -> Unit) {
    Column(Modifier.padding(horizontal = NotitiaTheme.spacing.l)) {
        Header(onSettings)
        EmptyState(
            message = stringResource(Res.string.meetings_empty),
            hint = stringResource(Res.string.meetings_on_device),
            modifier = Modifier.padding(top = NotitiaTheme.spacing.xxxl),
        )
    }
}

@Composable
private fun MeetingList(
    meetings: List<MeetingListItem>,
    onOpen: (MeetingListItem) -> Unit,
    onSettings: () -> Unit,
) {
    val now = remember(meetings) { Clock.System.now() }
    val groups = remember(meetings, now) { groupByDay(meetings, now, TimeZone.currentSystemDefault()) }
    val spacing = NotitiaTheme.spacing
    LazyColumn(
        // Room under the last card, so the record button never hides it.
        contentPadding = PaddingValues(
            start = spacing.l,
            end = spacing.l,
            bottom = NotitiaTheme.sizes.recordButton + spacing.xl * 2,
        ),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Header(onSettings)
                OnThisPhone(meetings.size)
            }
        }
        groups.forEach { (group, inGroup) ->
            item(key = group.toString()) {
                GroupTitle(group.label(), Modifier.padding(top = spacing.xl, bottom = spacing.s))
            }
            items(inGroup, key = { it.id }) { meeting ->
                MeetingCard(
                    meeting = meeting,
                    now = now,
                    onClick = { onOpen(meeting) },
                    modifier = Modifier.padding(bottom = spacing.s),
                )
            }
        }
    }
}

@NotitiaPreviews
@Composable
private fun MeetingsScreenPreview() = NotitiaTheme {
    MeetingsScreen(
        meetings = sampleMeetings(Clock.System.now()),
        recording = false,
        onOpen = {},
        onRecord = {},
        onSettings = {},
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}

@NotitiaPreviews
@Composable
private fun EmptyMeetingsScreenPreview() = NotitiaTheme {
    MeetingsScreen(
        meetings = emptyList(),
        recording = false,
        onOpen = {},
        onRecord = {},
        onSettings = {},
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}
