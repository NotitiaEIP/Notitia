package com.example.notitia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.meetings_title
import org.jetbrains.compose.resources.stringResource

// The title that says where we are, marked as a heading for screen readers.
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = NotitiaTheme.typography.display,
        modifier = modifier.semantics { heading() },
    )
}

@NotitiaPreviews
@Composable
private fun ScreenTitlePreview() = NotitiaTheme {
    ScreenTitle(
        text = stringResource(Res.string.meetings_title),
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
    )
}
