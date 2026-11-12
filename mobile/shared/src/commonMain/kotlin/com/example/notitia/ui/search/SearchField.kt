package com.example.notitia.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.arrow_upward
import notitia.shared.generated.resources.close
import notitia.shared.generated.resources.search
import notitia.shared.generated.resources.search_clear
import notitia.shared.generated.resources.search_field
import notitia.shared.generated.resources.search_field_follow_up
import notitia.shared.generated.resources.search_send
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val MAX_LINES = 4

// At the bottom of the screen, where the thumb is, as in Gemini and Claude. No label above it: its
// placeholder says what it does, and names it for screen readers.
@Composable
fun SearchField(
    state: TextFieldState,
    followUp: Boolean,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NotitiaTheme.colors
    val sizes = NotitiaTheme.sizes
    val spacing = NotitiaTheme.spacing
    val typography = NotitiaTheme.typography
    val shape = NotitiaTheme.shapes.searchBar
    // The screen's one gradient, the record button's: the same signature on Meetings and on Search.
    val outline = remember(colors) { Brush.horizontalGradient(colors.buttonGradient) }
    var focused by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.search_field)
    // The icon and the button line up with the first and the last line when the field grows.
    val lineBox = maxOf(sizes.field, with(LocalDensity.current) { typography.body.lineHeight.toDp() } + spacing.m * 2)

    BasicTextField(
        state = state,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label },
        textStyle = typography.body.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accentContent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Search,
        ),
        onKeyboardAction = { onSubmit() },
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = MAX_LINES),
        decorator = { innerTextField ->
            Row(
                Modifier
                    .fillMaxWidth()
                    // It floats over the content: a shadow in light, as for the recording mini bar.
                    .shadow(if (colors.isDark) sizes.none else sizes.raisedShadow, shape, clip = true)
                    .background(colors.surfaceRaised)
                    .border(sizes.gradientStroke, outline, shape)
                    .padding(start = spacing.l, end = spacing.xs),
            ) {
                Box(Modifier.height(lineBox), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(Res.drawable.search),
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(sizes.icon),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = lineBox)
                        .padding(horizontal = spacing.m, vertical = spacing.m),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (state.text.isEmpty()) {
                        Text(
                            text = stringResource(if (followUp) Res.string.search_field_follow_up else Res.string.search_field),
                            style = typography.body,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                Box(Modifier.align(Alignment.Bottom).height(lineBox), contentAlignment = Alignment.Center) {
                    when {
                        focused && state.text.isNotBlank() -> SendButton(onSubmit)
                        !focused && state.text.isNotEmpty() -> ClearButton(onClear)
                    }
                }
            }
        },
    )
}

@Composable
private fun SendButton(onClick: () -> Unit) {
    val sizes = NotitiaTheme.sizes
    val description = stringResource(Res.string.search_send)
    Box(
        modifier = Modifier
            .size(sizes.touchTarget)
            .clip(NotitiaTheme.shapes.full)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(sizes.buttonCompact)
                .background(NotitiaTheme.colors.accent, NotitiaTheme.shapes.full),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.arrow_upward),
                contentDescription = null,
                tint = NotitiaTheme.colors.onAccent,
                modifier = Modifier.size(sizes.iconInButton),
            )
        }
    }
}

@Composable
private fun ClearButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(NotitiaTheme.sizes.touchTarget)) {
        Icon(
            painter = painterResource(Res.drawable.close),
            contentDescription = stringResource(Res.string.search_clear),
            tint = NotitiaTheme.colors.textSecondary,
            modifier = Modifier.size(NotitiaTheme.sizes.icon),
        )
    }
}

@NotitiaPreviews
@Composable
private fun SearchFieldPreview() = NotitiaTheme {
    Column(
        modifier = Modifier.background(NotitiaTheme.colors.background).padding(NotitiaTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(NotitiaTheme.spacing.l),
    ) {
        SearchField(TextFieldState(), followUp = false, remember { FocusRequester() }, onSubmit = {}, onClear = {})
        SearchField(TextFieldState("budget"), followUp = false, remember { FocusRequester() }, onSubmit = {}, onClear = {})
        SearchField(TextFieldState(), followUp = true, remember { FocusRequester() }, onSubmit = {}, onClear = {})
    }
}
