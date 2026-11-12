package com.example.notitia.ui.components

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.reducedMotion

// Items fade in and out and the others slide into place; with reduced motion, only the fades. A card a swipe has
// already taken off the screen leaves at once.
@Composable
fun LazyItemScope.listMotion(swipedOff: Boolean = false): Modifier {
    val motion = NotitiaTheme.motion
    return Modifier.animateItem(
        fadeInSpec = motion.quick(),
        placementSpec = if (reducedMotion()) null else motion.standard(),
        fadeOutSpec = if (swipedOff) motion.instant() else motion.quick(),
    )
}
