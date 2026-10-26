package com.example.notitia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.example.notitia.ui.minutes.sampleSpeakers
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme

// "Clément" gives "C", "Marie Curie" gives "MC": the name next to it does the rest.
fun initials(name: String): String =
    name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

// The colour is never alone: a dot always sits next to the speaker's name.
@Composable
fun SpeakerDot(index: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(NotitiaTheme.sizes.speakerDot)
            .background(NotitiaTheme.colors.speaker(index), NotitiaTheme.shapes.full),
    )
}

@Composable
fun SpeakerChip(name: String, index: Int, modifier: Modifier = Modifier) {
    val colors = NotitiaTheme.colors
    val spacing = NotitiaTheme.spacing
    Row(
        modifier = modifier
            .background(colors.surface, NotitiaTheme.shapes.full)
            .padding(start = spacing.xs, top = spacing.xs, end = spacing.m, bottom = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(NotitiaTheme.sizes.speakerAvatar)
                .background(colors.speaker(index), NotitiaTheme.shapes.full)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(text = initials(name), style = NotitiaTheme.typography.badge, color = colors.onSpeaker, maxLines = 1)
        }
        Text(text = name, style = NotitiaTheme.typography.labelSmall)
    }
}

@NotitiaPreviews
@Composable
private fun SpeakerChipPreview() = NotitiaTheme {
    FlowRow(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
    ) {
        sampleSpeakers.forEachIndexed { index, name -> SpeakerChip(name, index) }
        SpeakerDot(0, Modifier.align(Alignment.CenterVertically))
    }
}
