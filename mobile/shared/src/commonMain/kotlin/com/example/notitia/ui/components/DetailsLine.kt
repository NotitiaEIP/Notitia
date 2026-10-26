package com.example.notitia.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.example.notitia.ui.theme.NotitiaTheme

private const val SEPARATOR = " · "

// Screen readers would read the middle dot aloud, so they get commas instead.
private const val SPOKEN_SEPARATOR = ", "

// "14:02 · 42 min · 4 participants": secondary details, with fixed-width digits so they line up.
@Composable
fun DetailsLine(
    parts: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = NotitiaTheme.typography.timestamp,
) {
    Text(
        text = parts.joinToString(SEPARATOR),
        style = style,
        color = NotitiaTheme.colors.textSecondary,
        modifier = modifier.semantics { contentDescription = parts.joinToString(SPOKEN_SEPARATOR) },
    )
}
