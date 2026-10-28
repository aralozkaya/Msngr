@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** The native rich-text editor with the profile page's Material outlined-field decoration. */
@Composable
internal fun MessengerPlusProfileField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    controller: MessengerPlusEditorController,
    label: String,
    emoticonSettings: EmoticonSettings,
    messengerPlusEnabled: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    supportingText: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    emoticonSheet: EmoticonSheetState? = null,
    onColors: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var focusInteraction by remember { mutableStateOf<FocusInteraction.Focus?>(null) }
    DisposableEffect(interactionSource) {
        onDispose {
            focusInteraction?.let { interactionSource.tryEmit(FocusInteraction.Unfocus(it)) }
        }
    }
    val combinedTrailingIcon: (@Composable () -> Unit)? =
        if (trailingIcon != null || emoticonSheet != null) {
            {
                Row(
                    modifier = Modifier.padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    trailingIcon?.invoke()
                    if (emoticonSheet != null) {
                        val emoticonsOpen = emoticonSheet.isOpenFor(controller)
                        IconButton(
                            onClick = { emoticonSheet.toggle(controller) },
                            modifier = Modifier.size(
                                IconButtonDefaults.smallContainerSize(
                                    widthOption = IconButtonDefaults.IconButtonWidthOption.Narrow,
                                ),
                            ),
                            enabled = enabled,
                        ) {
                            if (emoticonsOpen) {
                                Icon(
                                    imageVector = Icons.Default.Keyboard,
                                    contentDescription = "Show keyboard",
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.EmojiEmotions,
                                    contentDescription = "Show emoticons",
                                )
                            }
                        }
                    }
                }
            }
        } else {
            null
        }

    Box(
        modifier = modifier
            .padding(top = 8.dp)
            .defaultMinSize(minHeight = OutlinedTextFieldDefaults.MinHeight)
            .pointerInput(controller, enabled) {
                detectTapGestures {
                    if (enabled) controller.requestFocusAndShowKeyboard()
                }
            },
    ) {
        OutlinedTextFieldDefaults.DecorationBox(
            value = value.text.messengerPlusPlainText(),
            enabled = enabled,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            interactionSource = interactionSource,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = combinedTrailingIcon,
            innerTextField = {
                MsnEmoticonEditor(
                    value = value.text,
                    onValueChange = { source -> onValueChange(controller.snapshot().copy(text = source)) },
                    controller = controller,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
                    enabled = enabled,
                    convertTypedShortcuts = emoticonSettings.convertTypedShortcuts,
                    useOriginalEmoticons = emoticonSettings.useOriginalEmoticons,
                    messengerPlusEnabled = messengerPlusEnabled,
                    initialSelection = value.selection,
                    singleLine = true,
                    hint = "",
                    contentDescription = label,
                    contentPadding = PaddingValues(0.dp),
                    onColors = onColors,
                    onFocusChanged = { focused ->
                        if (focused && focusInteraction == null) {
                            val interaction = FocusInteraction.Focus()
                            focusInteraction = interaction
                            interactionSource.tryEmit(interaction)
                        } else if (!focused) {
                            focusInteraction?.let {
                                interactionSource.tryEmit(FocusInteraction.Unfocus(it))
                            }
                            focusInteraction = null
                        }
                    },
                )
            },
        )
    }
}
