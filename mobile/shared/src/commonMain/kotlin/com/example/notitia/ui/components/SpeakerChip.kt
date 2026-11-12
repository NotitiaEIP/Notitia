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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.example.notitia.ui.minutes.sampleSpeakers
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.speakers_more
import org.jetbrains.compose.resources.stringResource

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

// Decorative: the name always sits next to it. index is null for someone who is not one of the speakers.
@Composable
fun SpeakerAvatar(name: String, index: Int?, modifier: Modifier = Modifier) {
    val colors = NotitiaTheme.colors
    Box(
        modifier = modifier
            .size(NotitiaTheme.sizes.speakerAvatar)
            .background(index?.let(colors::speaker) ?: colors.textTertiary, NotitiaTheme.shapes.full)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(text = initials(name), style = NotitiaTheme.typography.badge, color = colors.onSpeaker, maxLines = 1)
    }
}

@Composable
fun SpeakerChip(name: String, index: Int, modifier: Modifier = Modifier) {
    val spacing = NotitiaTheme.spacing
    Row(
        modifier = modifier
            .background(NotitiaTheme.colors.surface, NotitiaTheme.shapes.full)
            .padding(start = spacing.xs, top = spacing.xs, end = spacing.m, bottom = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeakerAvatar(name, index)
        Text(text = name, style = NotitiaTheme.typography.labelSmall)
    }
}

private const val STACK_MAX = 3

// The people of a meeting, overlapping as in Slack and Google Calendar, each in their speaker colour, then "+1". Each
// face cuts a thin gap out of the one before it, so the card shows through, its chosen tint too, where a ring painted
// in the card's colour would not.
@Composable
fun SpeakerStack(names: List<String>, modifier: Modifier = Modifier) {
    val sizes = NotitiaTheme.sizes
    val shown = names.take(STACK_MAX)
    val more = names.size - shown.size
    val description = spokenList(names)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            horizontalArrangement = Arrangement.spacedBy(-sizes.stackOverlap),
        ) {
            shown.forEachIndexed { index, name ->
                SpeakerAvatar(
                    name = name,
                    index = index,
                    modifier = Modifier
                        .size(sizes.stackAvatar)
                        .drawBehind {
                            if (index > 0) {
                                val radius = size.minDimension / 2 + sizes.stackGap.toPx()
                                drawCircle(Color.Black, radius, blendMode = BlendMode.Clear)
                            }
                        },
                )
            }
        }
        if (more > 0) {
            Text(
                text = stringResource(Res.string.speakers_more, more),
                style = NotitiaTheme.typography.caption,
                color = NotitiaTheme.colors.textSecondary,
            )
        }
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
        SpeakerStack(sampleSpeakers.take(2))
        SpeakerStack(sampleSpeakers)
    }
}
