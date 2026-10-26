package com.example.notitia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.meetings_title
import notitia.shared.generated.resources.settings
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = NotitiaTheme.typography.display,
        modifier = modifier.semantics { heading() },
    )
}

// The large title of a tab, 8 dp under the system's safe area, with its buttons on the same line, as in
// WhatsApp's top bar. The buttons are centred on the first line of the title and their 48 dp target
// overflows it without pushing the content down, so every tab puts its title at the same height.
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val titleLine = with(LocalDensity.current) { NotitiaTheme.typography.display.lineHeight.toDp() }
    Row(
        modifier = modifier.fillMaxWidth().padding(top = NotitiaTheme.spacing.s),
        verticalAlignment = Alignment.Top,
    ) {
        ScreenTitle(title, Modifier.weight(1f))
        Row(
            modifier = Modifier.height(titleLine),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

// An icon button for the header: its icon lines up with the screen margin, its 48 dp target reaches into it.
@Composable
fun HeaderAction(icon: Painter, description: String, onClick: () -> Unit) {
    val sizes = NotitiaTheme.sizes
    IconButton(
        onClick = onClick,
        modifier = Modifier.offset(x = (sizes.touchTarget - sizes.icon) / 2).requiredSize(sizes.touchTarget),
    ) {
        Icon(
            painter = icon,
            contentDescription = description,
            tint = NotitiaTheme.colors.text,
            modifier = Modifier.size(sizes.icon),
        )
    }
}

@NotitiaPreviews
@Composable
private fun ScreenHeaderPreview() = NotitiaTheme {
    ScreenHeader(
        title = stringResource(Res.string.meetings_title),
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(horizontal = NotitiaTheme.spacing.l),
    ) {
        HeaderAction(
            icon = painterResource(Res.drawable.settings),
            description = stringResource(Res.string.settings),
            onClick = {},
        )
    }
}
