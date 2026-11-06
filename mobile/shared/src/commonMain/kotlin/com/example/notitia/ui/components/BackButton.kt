package com.example.notitia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.chevron_left
import notitia.shared.generated.resources.meetings_title
import notitia.shared.generated.resources.minutes_back_description
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

// Names the screen it goes back to, as the back button does on iPhone.
@Composable
fun BackButton(label: String, description: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = NotitiaTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = NotitiaTheme.sizes.touchTarget)
            .clickable(role = Role.Button, onClick = onBack)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(end = NotitiaTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.chevron_left),
            contentDescription = null,
            tint = colors.accentContent,
        )
        Text(
            text = label,
            style = NotitiaTheme.typography.label,
            color = colors.accentContent,
        )
    }
}

@NotitiaPreviews
@Composable
private fun BackButtonPreview() = NotitiaTheme {
    BackButton(
        label = stringResource(Res.string.meetings_title),
        description = stringResource(Res.string.minutes_back_description),
        onBack = {},
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.xs),
    )
}
