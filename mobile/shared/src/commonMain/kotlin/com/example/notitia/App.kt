package com.example.notitia

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.meetings_empty
import notitia.shared.generated.resources.meetings_on_device
import notitia.shared.generated.resources.meetings_title
import org.jetbrains.compose.resources.stringResource

@Composable
@Preview
fun App() {
    NotitiaTheme {
        Column(
            modifier = Modifier
                .background(NotitiaTheme.colors.background)
                .safeContentPadding()
                .fillMaxSize()
                .padding(horizontal = NotitiaTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
        ) {
            Text(
                text = stringResource(Res.string.meetings_title),
                style = NotitiaTheme.typography.display,
                modifier = Modifier
                    .padding(bottom = NotitiaTheme.spacing.xxxl)
                    .semantics { heading() },
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
    }
}
