package com.example.notitia.ui.minutes

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.then
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.example.notitia.ui.components.FocusFollowsKeyboard
import com.example.notitia.ui.theme.NotitiaPreviews
import com.example.notitia.ui.theme.NotitiaTheme
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.meeting_title_label
import org.jetbrains.compose.resources.stringResource

// Long enough for any meeting's name, short enough for a list row and a notification.
private const val MAX_LENGTH = 100

// The title is the field, as in Notes and Granola: nothing frames it, and a touch puts the cursor where it lands.
// The new title is kept when the field is left (Done, a touch beside it, the keyboard closed, the screen left).
// A blank title takes the old one back. Unchanged, a meeting never named keeps its date-based name, which the app
// can still translate. Each new value of editRequests selects the whole title for typing over it.
@Composable
fun MeetingTitle(
    title: String,
    onRename: (String) -> Unit,
    editRequests: Int,
    modifier: Modifier = Modifier,
) {
    val colors = NotitiaTheme.colors
    val field = rememberTextFieldState(title)
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    val shown by rememberUpdatedState(title)
    val rename by rememberUpdatedState(onRename)
    val label = stringResource(Res.string.meeting_title_label)

    fun keep() {
        val typed = field.text.toString().trim()
        if (typed.isNotEmpty() && typed != shown) rename(typed) else field.setTextAndPlaceCursorAtEnd(shown)
    }

    FocusFollowsKeyboard()
    // A title changed elsewhere shows at once, but never under the user's fingers.
    LaunchedEffect(title) { if (!editing) field.setTextAndPlaceCursorAtEnd(title) }
    LaunchedEffect(editRequests) {
        if (editRequests > 0) {
            field.edit { selectAll() }
            focus.requestFocus()
            keyboard?.show()
        }
    }
    DisposableEffect(Unit) { onDispose { if (editing) keep() } }

    BasicTextField(
        state = field,
        inputTransformation = remember { OneLine.then(InputTransformation.maxLength(MAX_LENGTH)) },
        textStyle = NotitiaTheme.typography.title1.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accentContent),
        lineLimits = TextFieldLineLimits.MultiLine(),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        onKeyboardAction = { focusManager.clearFocus() },
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged {
                if (editing && !it.isFocused) keep()
                editing = it.isFocused
            }
            .semantics {
                heading()
                contentDescription = label
            },
    )
}

private val LINE_BREAKS = Regex("[\r\n]+")

// A title wraps like the one it replaces, but has no line breaks, even pasted ones: Enter cannot add any.
private object OneLine : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val text = asCharSequence().toString()
        if (text.contains(LINE_BREAKS)) replace(0, length, text.replace(LINE_BREAKS, " "))
    }
}

@NotitiaPreviews
@Composable
private fun MeetingTitlePreview() = NotitiaTheme {
    MeetingTitle(
        title = "Point produit hebdomadaire avec l'équipe mobile",
        onRename = {},
        editRequests = 0,
        modifier = Modifier.background(NotitiaTheme.colors.background),
    )
}
