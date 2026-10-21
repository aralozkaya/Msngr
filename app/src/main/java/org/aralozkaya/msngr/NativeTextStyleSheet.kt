@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.pages.components.ActionSnackbarHost
import org.aralozkaya.msngr.pages.components.showActionSnackbar
import kotlin.math.roundToInt

@Composable
internal fun NativeTextStyleSheet(
    style: NativeTextStyle,
    onStyleChange: (NativeTextStyle) -> Unit,
    onDismissRequest: () -> Unit,
    onSizeNoticeRequested: () -> Boolean = { false },
) {
    var choosingColor by rememberSaveable { mutableStateOf(false) }
    var sizeInput by remember(style.size) { mutableStateOf(style.size.toString()) }
    val sizeValue = sizeInput.toIntOrNull()
    val validSize = sizeValue != null && sizeValue in NativeTextStyle.MIN_SIZE..NativeTextStyle.MAX_SIZE
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sizeNotice = stringResource(R.string.chat_text_size_local_notice)
    fun updateSize(updated: NativeTextStyle) {
        onStyleChange(updated)
        if (updated.size != style.size && onSizeNoticeRequested()) {
            scope.launch { snackbarHostState.showActionSnackbar(sizeNotice) }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        modifier = Modifier.testTag("native_text_style_sheet"),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                NativeFontStyleDropdown(style.fontStyle) { onStyleChange(style.withFontStyle(it)) }
                NativeStyleToggle(
                    label = "Strikeout",
                    checked = style.strikeout,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    onStyleChange(style.copy(strikeout = it))
                }
                NativeStyleToggle(
                    label = "Underline",
                    checked = style.underline,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    onStyleChange(style.copy(underline = it))
                }
                Row(
                    modifier = Modifier
                        .clickable(role = Role.Button) { choosingColor = true }
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Color", modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(height = 32.dp, width = 52.dp)
                            .background(Color(0xFF000000.toInt() or style.colorRgb), CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .semantics {
                                contentDescription = "Current color #${style.colorRgb.toString(16).padStart(6, '0')}"
                            },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Size", modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = sizeInput,
                        onValueChange = { input ->
                            sizeInput = input
                            input.toIntOrNull()
                                ?.takeIf { it in NativeTextStyle.MIN_SIZE..NativeTextStyle.MAX_SIZE }
                                ?.let { updateSize(style.copy(size = it)) }
                        },
                        modifier = Modifier.width(100.dp).testTag("native_text_size_input"),
                        singleLine = true,
                        label = { Text("Size") },
                        isError = !validSize,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Slider(
                    value = style.size.toFloat(),
                    onValueChange = {
                        val size = it.roundToInt()
                        sizeInput = size.toString()
                        updateSize(style.copy(size = size))
                    },
                    valueRange = NativeTextStyle.MIN_SIZE.toFloat()..NativeTextStyle.MAX_SIZE.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .semantics { contentDescription = "Text size" },
                )
                if (!validSize) {
                    Text(
                        "Enter a size from 8 to 72.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    onClick = {
                        sizeInput = NativeTextStyle.DEFAULT_SIZE.toString()
                        updateSize(NativeTextStyle())
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Reset styling")
                }
            }
            ActionSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
    if (choosingColor) {
        MessengerPlusCustomColorDialog(
            initialColor = checkNotNull(MessengerPlusTextCodec.colorValue("#${style.colorRgb.toString(16).padStart(6, '0')}")),
            applyLabel = "Apply color",
            onColorSelected = {
                onStyleChange(style.copy(colorRgb = it.argb and 0xFFFFFF))
                choosingColor = false
            },
            onDismissRequest = { choosingColor = false },
        )
    }
}

@Composable
private fun NativeFontStyleDropdown(selected: NativeFontStyle, onSelected: (NativeFontStyle) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Font Style", modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.testTag("native_font_style_dropdown"),
            ) {
                Text(selected.label)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuGroup(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    shapes = MenuDefaults.groupShape(0, 1),
                    interactionSource = interactionSource,
                ) {
                    NativeFontStyle.entries.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    option.label,
                                    fontWeight = FontWeight(option.weight),
                                    fontStyle = if (option.italic) FontStyle.Italic else FontStyle.Normal,
                                )
                            },
                            trailingIcon = if (option == selected) {
                                { Icon(Icons.Default.Check, contentDescription = "Selected") }
                            } else null,
                            shape = MenuDefaults.itemShape(index, NativeFontStyle.entries.size).shape,
                            onClick = {
                                expanded = false
                                onSelected(option)
                            },
                            modifier = Modifier.testTag("native_font_style_${option.name}"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeStyleToggle(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
