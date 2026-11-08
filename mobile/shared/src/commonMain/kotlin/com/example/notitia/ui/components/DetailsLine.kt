package com.example.notitia.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.example.notitia.ui.theme.NotitiaTheme

private const val SEPARATOR = " · "

// Screen readers would read the middle dot aloud, so they get commas instead.
private const val SPOKEN_SEPARATOR = ", "

// What a screen reader says for parts shown side by side: a short pause between each.
fun spokenList(parts: List<String>): String = parts.joinToString(SPOKEN_SEPARATOR)

// "14:02 · 42 min · 4 participants": secondary details, with fixed-width digits so they line up.
// alert comes last, in red: something late ("2 en retard"), which its words say too.
@Composable
fun DetailsLine(
    parts: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = NotitiaTheme.typography.timestamp,
    alert: String? = null,
    color: Color = NotitiaTheme.colors.textSecondary,
) {
    val alertStyle = SpanStyle(color = NotitiaTheme.colors.red, fontWeight = FontWeight.SemiBold)
    val all = parts + listOfNotNull(alert)
    Text(
        text = buildAnnotatedString {
            append(parts.joinToString(SEPARATOR))
            if (alert != null) {
                if (parts.isNotEmpty()) append(SEPARATOR)
                withStyle(alertStyle) { append(alert) }
            }
        },
        style = style,
        color = color,
        modifier = modifier.semantics { contentDescription = spokenList(all) },
    )
}
