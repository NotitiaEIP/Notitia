package com.example.notitia.ui.meetings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.notitia.ui.components.RecordButton
import com.example.notitia.ui.components.ScreenTitle
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.meetings_empty
import notitia.shared.generated.resources.meetings_on_device
import notitia.shared.generated.resources.meetings_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun MeetingsScreen(onRecord: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = NotitiaTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
        ) {
            ScreenTitle(
                text = stringResource(Res.string.meetings_title),
                modifier = Modifier.padding(bottom = NotitiaTheme.spacing.xxxl),
            )
            Text(
                text = stringResource(Res.string.meetings_empty),
                style = NotitiaTheme.typography.body,
            )
            Text(
                text = stringResource(Res.string.meetings_on_device),
                style = NotitiaTheme.typography.callout,
                color = NotitiaTheme.colors.textSecondary,
            )
        }
        RecordButton(
            onClick = onRecord,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = NotitiaTheme.spacing.xl),
        )
    }
}
