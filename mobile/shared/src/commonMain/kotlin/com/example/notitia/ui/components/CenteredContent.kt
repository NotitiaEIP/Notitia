package com.example.notitia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.notitia.ui.theme.NotitiaTheme

// On a tablet or an open foldable, the phone layout stays in the middle rather than stretched, until large screens
// get their own. The margins are painted here, not by the window, so they follow the theme chosen in the app.
@Composable
fun CenteredContent(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(NotitiaTheme.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.fillMaxHeight().widthIn(max = NotitiaTheme.sizes.contentMaxWidth).fillMaxWidth()) {
            content()
        }
    }
}
