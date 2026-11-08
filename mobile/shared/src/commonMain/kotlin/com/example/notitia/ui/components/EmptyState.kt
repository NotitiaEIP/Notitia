package com.example.notitia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.actions_empty
import notitia.shared.generated.resources.actions_empty_hint
import org.jetbrains.compose.resources.stringResource

// What is missing in one sentence, then where it will come from.
@Composable
fun EmptyState(message: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s)) {
        Text(text = message, style = NotitiaTheme.typography.body)
        Text(text = hint, style = NotitiaTheme.typography.callout, color = NotitiaTheme.colors.textSecondary)
    }
}

@NotitiaPreviews
@Composable
private fun EmptyStatePreview() = NotitiaTheme {
    EmptyState(
        message = stringResource(Res.string.actions_empty),
        hint = stringResource(Res.string.actions_empty_hint),
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
    )
}
