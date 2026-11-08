package com.example.notitia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.group_today
import org.jetbrains.compose.resources.stringResource

@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier, color: Color = NotitiaTheme.colors.textTertiary) {
    Text(
        text = text,
        style = NotitiaTheme.typography.labelSmall,
        color = color,
        modifier = modifier.semantics { heading() },
    )
}

@NotitiaPreviews
@Composable
private fun GroupTitlePreview() = NotitiaTheme {
    GroupTitle(
        text = stringResource(Res.string.group_today),
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
    )
}
