package com.example.notitia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.minutes_decisions
import notitia.shared.generated.resources.minutes_summary
import org.jetbrains.compose.resources.stringResource

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, count: Int? = null) {
    val colors = NotitiaTheme.colors
    Row(
        // One heading for screen readers: "Actions, 3".
        modifier = modifier.semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = NotitiaTheme.typography.title3)
        if (count != null) {
            Text(
                text = count.toString(),
                style = NotitiaTheme.typography.labelSmall,
                color = colors.accentContent,
                modifier = Modifier
                    .background(colors.accentSoft, NotitiaTheme.shapes.badge)
                    .heightIn(min = NotitiaTheme.sizes.badge)
                    .padding(horizontal = NotitiaTheme.spacing.s, vertical = NotitiaTheme.spacing.xxs),
            )
        }
    }
}

@NotitiaPreviews
@Composable
private fun SectionHeaderPreview() = NotitiaTheme {
    Column(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.l),
    ) {
        SectionHeader(stringResource(Res.string.minutes_decisions), count = 2)
        SectionHeader(stringResource(Res.string.minutes_summary))
    }
}
