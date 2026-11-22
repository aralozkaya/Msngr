@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.pages.components.KeyboardPickerSheet
import org.aralozkaya.msngr.pages.components.KeyboardSheetTile
import org.aralozkaya.msngr.pages.components.KeyboardSheetTileGrid
import org.aralozkaya.msngr.pages.components.LocalKeyboardSheetHost

/**
 * Remembers the selection that opened the color sheet so closing the selection toolbar does not
 * lose the range being colored.
 */
internal class MessengerPlusColorSheetState {
    private var controller: MessengerPlusEditorController? = null
    private var selectedValue by mutableStateOf<TextFieldValue?>(null)
    val visible: Boolean get() = selectedValue != null
    val hasSelection: Boolean get() = selectedValue?.selection?.collapsed == false
    var initialStyle by mutableStateOf(MessengerPlusStyle())
        private set

    fun isOpenFor(controller: MessengerPlusEditorController): Boolean =
        visible && this.controller === controller

    /** The keyboard sheet scaffold closes the keyboard once the sheet has taken its slot. */
    fun open(controller: MessengerPlusEditorController) {
        this.controller = controller
        selectedValue = controller.snapshot()
        initialStyle = controller.activeStyle
    }

    fun dismiss() {
        selectedValue = null
        controller = null
    }

    fun applyForeground(color: MessengerPlusColorValue) = apply { applyForeground(color) }
    fun toggle(control: Char) = apply { toggle(control) }
    fun clearFormatting() = apply { removeAllFormatting() }

    fun applyBackground(color: MessengerPlusColorValue) = apply { applyBackground(color) }

    fun applyGradient(start: MessengerPlusColorValue, end: MessengerPlusColorValue, background: Boolean) =
        apply { applyGradient(start, end, background) }

    private fun apply(action: MessengerPlusEditorController.() -> Unit) {
        val target = controller ?: return
        val selection = selectedValue ?: return
        if (target.snapshot().text != selection.text) {
            dismiss()
            return
        }
        target.restoreSelection(selection.selection)
        target.action()
        selectedValue = target.snapshot()
        initialStyle = target.activeStyle
    }
}

@Composable
internal fun rememberMessengerPlusColorSheetState(): MessengerPlusColorSheetState =
    remember { MessengerPlusColorSheetState() }

/** Selection-scoped Plus! decorations, colors and gradients, hosted in the keyboard slot. */
@Composable
internal fun MessengerPlusColorSheet(state: MessengerPlusColorSheetState) {
    var background by rememberSaveable { mutableStateOf(false) }
    var gradient by rememberSaveable { mutableStateOf(false) }
    var gradientStartInput by rememberSaveable { mutableStateOf("04") }
    var gradientEndInput by rememberSaveable { mutableStateOf("12") }
    val footer: (@Composable ColumnScope.() -> Unit)? = if (gradient) {
            {
                val gradientStart = MessengerPlusTextCodec.colorValue(gradientStartInput)
                val gradientEnd = MessengerPlusTextCodec.colorValue(gradientEndInput)
                HorizontalDivider()
                FilledTonalButton(
                    onClick = {
                        if (gradientStart != null && gradientEnd != null) {
                            state.applyGradient(gradientStart, gradientEnd, background)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.hasSelection && gradientStart != null && gradientEnd != null,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.plus_apply_gradient))
                }
                if (!state.hasSelection) {
                    Text(
                        stringResource(R.string.plus_select_text_gradient),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            null
        }
    val content: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MessengerPlusDecorationButtons(state, modifier = Modifier.weight(1f))
            val clearFormattingLabel = stringResource(R.string.plus_clear_formatting)
            MessengerPlusFormattingTooltip(clearFormattingLabel) {
                FilledIconButton(
                    onClick = state::clearFormatting,
                    shapes = IconButtonDefaults.shapes(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Icon(Icons.Default.FormatClear, contentDescription = clearFormattingLabel)
                }
            }
        }
        HorizontalDivider()
        MessengerPlusColorOptions(
            state = state,
            background = background,
            onBackgroundChange = { background = it },
            gradient = gradient,
            onGradientChange = { gradient = it },
            gradientStartInput = gradientStartInput,
            onGradientStartChange = { gradientStartInput = it },
            gradientEndInput = gradientEndInput,
            onGradientEndChange = { gradientEndInput = it },
        )
    }
    if (LocalKeyboardSheetHost.current == null) {
        // Compact status windows do not have a page-level keyboard sheet host.
        if (state.visible) ModalBottomSheet(onDismissRequest = state::dismiss) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
                footer?.invoke(this)
            }
        }
    } else {
        KeyboardPickerSheet(
            visible = state.visible,
            onDismissRequest = state::dismiss,
            paneTitle = stringResource(R.string.format_plus),
            testTag = MESSENGER_PLUS_COLOR_SHEET_TAG,
            expandable = true,
            keepOpenOnInputTap = true,
            footer = footer,
            content = content,
        )
    }
}

@Composable
private fun MessengerPlusFormattingTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        content()
    }
}

@Composable
private fun MessengerPlusDecorationButtons(
    state: MessengerPlusColorSheetState,
    modifier: Modifier = Modifier,
) {
    val decorations = listOf(
        Triple(stringResource(R.string.format_bold), MessengerPlusTextCodec.BOLD_CONTROL, state.initialStyle.bold),
        Triple(stringResource(R.string.format_italic), MessengerPlusTextCodec.ITALIC_CONTROL, state.initialStyle.italic),
        Triple(stringResource(R.string.format_strikethrough), MessengerPlusTextCodec.STRIKEOUT_CONTROL, state.initialStyle.strikeout),
        Triple(stringResource(R.string.format_underline), MessengerPlusTextCodec.UNDERLINE_CONTROL, state.initialStyle.underline),
    )
    val icons = listOf(
        Icons.Default.FormatBold,
        Icons.Default.FormatItalic,
        Icons.Default.FormatStrikethrough,
        Icons.Default.FormatUnderlined,
    )
    ButtonGroup(
        overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        expandedRatio = 0f,
    ) {
        decorations.forEachIndexed { index, (label, control, active) ->
            customItem(
                buttonGroupContent = {
                    MessengerPlusFormattingTooltip(label) {
                        ToggleButton(
                            checked = active,
                            onCheckedChange = { state.toggle(control) },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                decorations.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                        ) {
                            Icon(icons[index], contentDescription = label)
                        }
                    }
                },
                menuContent = { menuState ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = { Icon(icons[index], contentDescription = null) },
                        trailingIcon = if (active) {
                            { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.common_selected)) }
                        } else null,
                        onClick = {
                            state.toggle(control)
                            menuState.dismiss()
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun MessengerPlusColorOptions(
    state: MessengerPlusColorSheetState,
    background: Boolean,
    onBackgroundChange: (Boolean) -> Unit,
    gradient: Boolean,
    onGradientChange: (Boolean) -> Unit,
    gradientStartInput: String,
    onGradientStartChange: (String) -> Unit,
    gradientEndInput: String,
    onGradientEndChange: (String) -> Unit,
) {
    var textColorInput by rememberSaveable {
        mutableStateOf(state.initialStyle.foreground?.wireToken ?: "#FF0000")
    }
    var backgroundColorInput by rememberSaveable {
        mutableStateOf(state.initialStyle.background?.wireToken ?: "#FFFF00")
    }
    var selectingGradientEnd by rememberSaveable { mutableStateOf(false) }
    var customColor by rememberSaveable { mutableStateOf(false) }
    val colorInput = if (background) backgroundColorInput else textColorInput
    val color = MessengerPlusTextCodec.colorValue(colorInput)
    val gradientStart = MessengerPlusTextCodec.colorValue(gradientStartInput)
    val gradientEnd = MessengerPlusTextCodec.colorValue(gradientEndInput)
    val selectedColor = if (gradient) {
        if (selectingGradientEnd) gradientEnd else gradientStart
    } else {
        color
    }
    fun chooseColor(selected: MessengerPlusColorValue) {
        if (gradient) {
            if (selectingGradientEnd) onGradientEndChange(selected.wireToken)
            else onGradientStartChange(selected.wireToken)
        } else if (background) {
            backgroundColorInput = selected.wireToken
            state.applyBackground(selected)
        } else {
            textColorInput = selected.wireToken
            state.applyForeground(selected)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.plus_colors_plural),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        FilterChip(
            selected = gradient,
            onClick = { onGradientChange(!gradient) },
            label = { Text(stringResource(R.string.plus_gradient)) },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        ToggleButton(
            checked = !background,
            onCheckedChange = { onBackgroundChange(false) },
            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
        ) {
            Text(stringResource(R.string.plus_target_text))
        }
        ToggleButton(
            checked = background,
            onCheckedChange = { onBackgroundChange(true) },
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
        ) {
            Text(stringResource(R.string.plus_target_background))
        }
    }
    if (gradient) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MessengerPlusGradientEndpoint(
                label = stringResource(R.string.plus_gradient_start),
                color = gradientStart,
                selected = !selectingGradientEnd,
                onClick = { selectingGradientEnd = false },
                modifier = Modifier.weight(1f).testTag(MESSENGER_PLUS_GRADIENT_START_TAG),
            )
            MessengerPlusGradientEndpoint(
                label = stringResource(R.string.plus_gradient_end),
                color = gradientEnd,
                selected = selectingGradientEnd,
                onClick = { selectingGradientEnd = true },
                modifier = Modifier.weight(1f).testTag(MESSENGER_PLUS_GRADIENT_END_TAG),
            )
        }
    }
    MessengerPlusPaletteGrid(
        selectedColor = selectedColor,
        onColorSelected = { chooseColor(it.asValue()) },
        onCustomColor = { customColor = true },
    )
    if (customColor) {
        MessengerPlusCustomColorDialog(
            initialColor = selectedColor ?: checkNotNull(MessengerPlusTextCodec.paletteColor(4)),
            applyLabel = stringResource(
                if (gradient) {
                    if (selectingGradientEnd) R.string.plus_use_for_end else R.string.plus_use_for_start
                } else {
                    R.string.plus_apply_color
                },
            ),
            onColorSelected = {
                chooseColor(it)
                customColor = false
            },
            onDismissRequest = { customColor = false },
        )
    }
}

@Composable
private fun MessengerPlusGradientEndpoint(
    label: String,
    color: MessengerPlusColorValue?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(R.string.plus_gradient_chip_label, label, color?.wireToken.orEmpty())) },
        leadingIcon = {
            Box(
                Modifier.size(20.dp)
                    .background(color?.let { Color(it.argb) } ?: Color.Transparent, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun MessengerPlusPaletteGrid(
    selectedColor: MessengerPlusColorValue?,
    onColorSelected: (MessengerPlusPaletteColor) -> Unit,
    onCustomColor: () -> Unit,
) {
    val tiles = listOf<MessengerPlusPaletteColor?>(null) + MessengerPlusTextCodec.palette
    val customSelected = selectedColor != null &&
        MessengerPlusTextCodec.palette.none { it.argb == selectedColor.argb }
    val customColorLabel = stringResource(R.string.plus_custom_color)
    val customShortLabel = stringResource(R.string.plus_custom_short)
    KeyboardSheetTileGrid(items = tiles) { color ->
        val swatch = color?.let { Color(it.argb) }
            ?: if (customSelected) Color(checkNotNull(selectedColor).argb)
            else MaterialTheme.colorScheme.secondaryContainer
        val isSelected = if (color == null) customSelected else selectedColor?.argb == color.argb
        val tileDesc = color?.let {
            val labelText = it.labelRes?.let { res -> stringResource(res) } ?: it.label
            stringResource(R.string.plus_palette_tile_desc, it.wireToken, labelText)
        } ?: customColorLabel
        KeyboardSheetTile(
            description = tileDesc,
            isSelected = isSelected,
            color = swatch,
            contentColor = if (swatch.luminance() > 0.45f) Color.Black else Color.White,
            border = BorderStroke(
                if (isSelected) 3.dp else 1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            ),
            onClick = { if (color == null) onCustomColor() else onColorSelected(color) },
        ) {
            Text(color?.wireToken ?: customShortLabel, style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal const val MESSENGER_PLUS_COLOR_SHEET_TAG = "messenger_plus_color_sheet"
internal val MESSENGER_PLUS_COLOR_SHEET_HEIGHT = 420.dp
internal const val MESSENGER_PLUS_GRADIENT_START_TAG = "messenger_plus_gradient_start"
internal const val MESSENGER_PLUS_GRADIENT_END_TAG = "messenger_plus_gradient_end"
