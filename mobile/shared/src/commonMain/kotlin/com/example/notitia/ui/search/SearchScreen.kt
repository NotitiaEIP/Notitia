package com.example.notitia.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.notitia.ui.components.ScreenTitle
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.search_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = NotitiaTheme.spacing.l)) {
        ScreenTitle(stringResource(Res.string.search_title))
    }
}
