package com.example.notitia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.actions_empty
import org.jetbrains.compose.resources.stringResource

// One sentence, nothing more: what fills the screen is already in sight (the record button, the search bar).
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Text(text = message, style = NotitiaTheme.typography.body, modifier = modifier)
}

@NotitiaPreviews
@Composable
private fun EmptyStatePreview() = NotitiaTheme {
    EmptyState(
        message = stringResource(Res.string.actions_empty),
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
    )
}
