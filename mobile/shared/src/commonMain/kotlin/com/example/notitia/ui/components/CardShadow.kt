package com.example.notitia.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import com.example.notitia.ui.theme.NotitiaTheme

// In dark a card is lighter than the background, which is enough to set it apart.
@Composable
fun Modifier.cardShadow(shape: Shape): Modifier {
    val sizes = NotitiaTheme.sizes
    return shadow(if (NotitiaTheme.colors.isDark) sizes.none else sizes.cardShadow, shape, clip = true)
}
