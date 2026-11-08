@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.notitia.ui.actions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import com.example.notitia.ui.components.SpeakerAvatar
import com.example.notitia.ui.components.rememberTextShare
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import com.example.notitia.ui.theme.shareIcon
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.nudge_send
import notitia.shared.generated.resources.nudge_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val DISABLED_ALPHA = 0.4f

// As little to read as a message about to leave: whom it is for, with their colour, and the send button; then the
// message itself in a bubble, as sent messages look, which a touch lets change. The sheet is as tall as that.
// The send button hands it to the system's share sheet; this sheet slides away once it is sent.
@Composable
fun NudgeSheet(nudge: Nudge, reveal: SheetReveal, onDismiss: () -> Unit) {
    val sheetState = rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    val share = rememberTextShare()
    val keyboard = LocalSoftwareKeyboardController.current
    val words = rememberNudgeWords()
    // Saved, so a rotation keeps the edits. The caller keys the sheet on whom it is about.
    val message = rememberTextFieldState(nudgeText(nudge, words))
    val spacing = NotitiaTheme.spacing
    val title = stringResource(Res.string.nudge_title, nudge.name)
    RevealingSheet(sheetState, reveal, onDismiss) { measured ->
        Column(
            modifier = measured
                .fillMaxWidth()
                .semantics { paneTitle = title }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(start = spacing.xl, end = spacing.xl, bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.l),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpeakerAvatar(nudge.name, nudge.speaker, Modifier.size(NotitiaTheme.sizes.personAvatar))
                Text(
                    text = nudge.name,
                    style = NotitiaTheme.typography.title3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SendButton(
                    description = stringResource(Res.string.nudge_send, nudge.name),
                    enabled = message.text.isNotBlank(),
                ) {
                    keyboard?.hide()
                    share(message.text.toString()) {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    }
                }
            }
            MessageBubble(
                message,
                Modifier
                    .weight(1f, fill = false)
                    .padding(start = spacing.xxl),
            )
        }
    }
}

// Sent messages sit on the right, on the accent's soft tint. The text field fills the bubble, so a touch anywhere
// on it starts editing; past the height of the sheet, the text scrolls inside.
@Composable
private fun MessageBubble(message: TextFieldState, modifier: Modifier = Modifier) {
    val colors = NotitiaTheme.colors
    BasicTextField(
        state = message,
        textStyle = NotitiaTheme.typography.body.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier
            .fillMaxWidth()
            .clip(NotitiaTheme.shapes.bubble)
            .background(colors.accentSoft)
            .padding(horizontal = NotitiaTheme.spacing.l, vertical = NotitiaTheme.spacing.m),
    )
}

// A round button with the platform's share icon only, as the send button of Messages: its name is for screen
// readers. The share icon says the message leaves through another app.
@Composable
private fun SendButton(description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = NotitiaTheme.colors
    val sizes = NotitiaTheme.sizes
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else DISABLED_ALPHA,
        animationSpec = NotitiaTheme.motion.quick(),
        label = "send alpha",
    )
    Box(
        modifier = Modifier
            .size(sizes.touchTarget)
            .alpha(alpha)
            .clip(NotitiaTheme.shapes.full)
            .background(colors.accent)
            .clickable(
                interactionSource = null,
                indication = ripple(color = colors.onAccent),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(shareIcon),
            contentDescription = null,
            tint = colors.onAccent,
            modifier = Modifier.size(sizes.iconInButton),
        )
    }
}

@NotitiaPreviews
@Composable
private fun NudgeSheetContentPreview() = NotitiaTheme {
    val nudge = Nudge(
        name = "Nathan",
        speaker = 1,
        meetings = listOf(
            NudgeMeeting(
                title = "Point produit",
                day = LocalDate(2026, 11, 3),
                lines = listOf(NudgeLine("Envoyer la maquette à l’équipe", LocalDate(2026, 11, 7))),
            ),
        ),
    )
    val message = rememberTextFieldState(nudgeText(nudge, rememberNudgeWords()))
    Column(
        modifier = Modifier.background(NotitiaTheme.colors.surfaceRaised).padding(NotitiaTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.l),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeakerAvatar(nudge.name, nudge.speaker, Modifier.size(NotitiaTheme.sizes.personAvatar))
            Text(nudge.name, style = NotitiaTheme.typography.title3, modifier = Modifier.weight(1f))
            SendButton(description = "", enabled = true) {}
        }
        MessageBubble(message, Modifier.padding(start = NotitiaTheme.spacing.xxl))
    }
}
