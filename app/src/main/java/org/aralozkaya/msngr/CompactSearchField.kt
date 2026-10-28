package org.aralozkaya.msngr

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** Compact Material decoration around the app's glyph-rendering, selection-aware editor. */
@Composable
internal fun CompactSearchField(
    state: TextFieldState,
    placeholder: String,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    controller: MessengerPlusEditorController = remember { MessengerPlusEditorController() },
    onFocusChanged: (Boolean) -> Unit = {},
    leadingIcon: @Composable () -> Unit = {
        Icon(Icons.Default.Search, contentDescription = null)
    },
) {
    val interactionSource = remember { MutableInteractionSource() }
    var focusInteraction by remember { mutableStateOf<FocusInteraction.Focus?>(null) }
    val context = LocalContext.current
    val settings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    DisposableEffect(interactionSource) {
        onDispose {
            focusInteraction?.let { interactionSource.tryEmit(FocusInteraction.Unfocus(it)) }
        }
    }
    val textStyle = MaterialTheme.typography.bodyMedium
    val minimumHeight = with(LocalDensity.current) {
        (textStyle.lineHeight.toDp() + 16.dp).coerceAtLeast(48.dp)
    }
    val colors = SearchBarDefaults.colors().inputFieldColors
    val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        modifier = modifier
            .heightIn(min = minimumHeight)
            .semantics(mergeDescendants = true) {}
            .pointerInput(controller, enabled) {
                detectTapGestures { if (enabled) controller.requestFocusAndShowKeyboard() }
            },
    ) {
        CompositionLocalProvider(LocalTextStyle provides textStyle, LocalContentColor provides contentColor) {
            TextFieldDefaults.decorator(
                state = state,
                enabled = enabled,
                lineLimits = TextFieldLineLimits.SingleLine,
                outputTransformation = null,
                interactionSource = interactionSource,
                placeholder = { Text(placeholder, style = textStyle) },
                leadingIcon = leadingIcon,
                trailingIcon = {
                    if (state.text.isNotEmpty()) {
                        IconButton(
                            onClick = { controller.replaceValue(TextFieldValue("")) },
                            enabled = enabled,
                        ) {
                            Icon(Icons.Default.Close, contentDescription = clearContentDescription)
                        }
                    }
                },
                colors = colors,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                container = {
                    TextFieldDefaults.Container(
                        enabled = enabled,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = colors,
                        shape = CircleShape,
                        focusedIndicatorLineThickness = 0.dp,
                        unfocusedIndicatorLineThickness = 0.dp,
                    )
                },
            ).Decoration {
                MsnEmoticonEditor(
                    value = state.text.toString(),
                    onValueChange = { value ->
                        state.edit {
                            replace(0, length, value)
                            val nativeSelection = controller.snapshot().selection
                            selection = TextRange(
                                nativeSelection.start.coerceIn(0, length),
                                nativeSelection.end.coerceIn(0, length),
                            )
                        }
                    },
                    controller = controller,
                    initialSelection = state.selection,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    convertTypedShortcuts = settings.convertTypedShortcuts,
                    useOriginalEmoticons = settings.useOriginalEmoticons,
                    messengerPlusEnabled = false,
                    hideControlCharacters = false,
                    allowUnicodeEmoji = true,
                    singleLine = true,
                    hint = "",
                    contentDescription = placeholder,
                    contentPadding = PaddingValues(0.dp),
                    onSearch = controller::hideKeyboard,
                    onSelectionChanged = { selection ->
                        val value = controller.snapshot().text
                        state.edit {
                            if (toString() != value) replace(0, length, value)
                            this.selection = selection
                        }
                    },
                    onFocusChanged = { focused ->
                        if (focused && focusInteraction == null) {
                            val interaction = FocusInteraction.Focus()
                            focusInteraction = interaction
                            interactionSource.tryEmit(interaction)
                        } else if (!focused) {
                            focusInteraction?.let { interactionSource.tryEmit(FocusInteraction.Unfocus(it)) }
                            focusInteraction = null
                        }
                        onFocusChanged(focused)
                    },
                )
            }
        }
    }
}
