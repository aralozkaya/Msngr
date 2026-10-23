@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.aralozkaya.msngr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import org.aralozkaya.msngr.ui.theme.seededColorScheme
import java.util.Locale

internal val MessengerMainColors = listOf(
    "Messenger blue" to 0xFF74B4EF, "Ice blue" to 0xFF8BC9E8,
    "Lavender" to 0xFFABA0DF, "Aqua" to 0xFF73C7C7,
    "Green" to 0xFF8DCA83, "Yellow" to 0xFFE3C970,
    "Peach" to 0xFFE9B087, "Orange" to 0xFFE59A6A,
    "Rose" to 0xFFD98F9D, "Coral" to 0xFFDB7D8C,
    "Pink" to 0xFFD69ACE, "Purple" to 0xFFB88DD9,
    "Mauve" to 0xFFBC9EBF, "Silver" to 0xFF8A929B,
)

/** The selected option owns its expanding surface; AnimatedVisibility uses its defaults. */
@Composable
internal fun AppearanceColorOption(mode: ColorMode, index: Int, count: Int, appearance: AppAppearance) {
    val context = LocalContext.current
    val selected = appearance.colorMode == mode
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    val colors = ListItemDefaults.segmentedColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = if (selected) shapes.selectedShape else shapes.shape,
        color = if (selected) colors.selectedContainerColor else colors.containerColor,
        contentColor = if (selected) colors.selectedContentColor else colors.contentColor,
    ) {
        Column {
            SegmentedListItem(
                selected = selected,
                onClick = { AppearancePrefs.setColorMode(context, mode) },
                shapes = shapes,
                colors = colors.copy(containerColor = Color.Transparent, selectedContainerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().testTag("color_mode_${mode.name}"),
                supportingContent = { Text(when (mode) {
                    ColorMode.Default -> "Messenger colors or a custom main color"
                    ColorMode.Dynamic -> "Colors from your device wallpaper"
                    ColorMode.Scenes -> "Classic scenes with matching app colors"
                }) },
                trailingContent = { RadioButton(selected = selected, onClick = null) },
                content = { Text(mode.label) },
            )
            AnimatedVisibility(visible = selected && mode != ColorMode.Dynamic) {
                AppearanceColorControls(appearance.copy(colorMode = mode))
            }
        }
    }
}

@Composable
internal fun AppearanceColorControls(appearance: AppAppearance) {
    val context = LocalContext.current
    var showScenes by rememberSaveable { mutableStateOf(false) }
    var showColor by rememberSaveable { mutableStateOf(false) }
    val scene = messengerScene(appearance.sceneId)
    val sceneInk = sceneContentColor()
    val isScene = appearance.colorMode == ColorMode.Scenes
    val override = if (isScene) appearance.sceneColorArgb else appearance.customColorArgb
    val seed = override ?: if (isScene) scene.seedArgb else 0xFF74B4EF.toInt()
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("color_controls_${appearance.colorMode.name}"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (isScene) {
            Card(onClick = { showScenes = true }, modifier = Modifier.fillMaxWidth().testTag("choose_scene"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                SceneBackdrop(scene, Modifier.fillMaxWidth().height(128.dp)) {
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        SceneText(scene.name, style = MaterialTheme.typography.titleLarge, color = sceneInk)
                        SceneText(scene.collectionName, style = MaterialTheme.typography.labelMedium, color = sceneInk)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Choose a scene", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                }
            }
            Column {
                SceneOptionSwitch(
                    checked = appearance.showSceneInConversations,
                    onCheckedChange = { AppearancePrefs.setShowSceneInConversations(context, it) },
                    modifier = Modifier.testTag("scene_in_conversations"),
                    title = "Show scene in chats",
                    description = "Show the scene behind the name at the top of each chat.",
                )
                SceneOptionSwitch(
                    checked = appearance.darkenScenesInDarkMode,
                    onCheckedChange = { AppearancePrefs.setDarkenScenesInDarkMode(context, it) },
                    modifier = Modifier.testTag("darken_scenes"),
                    title = "Dark mode tint",
                    description = "Darken scene images on both screens in dark mode.",
                )
            }
        }
        Text("Main color", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Text(if (isScene && override == null) "Colors from ${scene.name}"
            else "Applied throughout the app in light and dark themes.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MessengerMainColors.forEach { (name, argb) ->
                val selected = override == argb.toInt()
                Box(Modifier.size(48.dp).selectable(selected, role = Role.RadioButton,
                    onClick = { AppearancePrefs.setMainColor(context, argb.toInt()) })
                    .semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp).background(Color(argb), CircleShape)
                        .border(if (selected) 3.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (selected) Icon(Icons.Default.Check, null, tint = SceneInk, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { showColor = true }, shapes = ButtonDefaults.shapes(),
                modifier = Modifier.testTag("custom_main_color")) { Text("Custom color") }
            if (override != null) TextButton(onClick = { AppearancePrefs.setMainColor(context, null) },
                shapes = ButtonDefaults.shapes()) { Text(if (isScene) "Use scene colors" else "Reset color") }
        }
    }
    if (showScenes) ScenePickerDialog(appearance.sceneId,
        onSelect = { AppearancePrefs.setScene(context, it.id); showScenes = false },
        onDismiss = { showScenes = false })
    if (showColor) MainColorDialog(seed, onSelect = {
        AppearancePrefs.setMainColor(context, it)
        showColor = false
    }, onDismiss = { showColor = false })
}

/** Plain switch rows inherit the expanded option's surface and content colors. */
@Composable
private fun SceneOptionSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun ScenePickerDialog(selectedId: String, onSelect: (MessengerScene) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(query) {
        MessengerScenes.filter { it.name.contains(query, true) || it.group.contains(query, true) ||
            it.collectionName.contains(query, true) }.groupBy { it.collectionName }
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnClickOutside = false)) {
        val view = LocalView.current
        val window = (view.parent as DialogWindowProvider).window
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
        Surface(Modifier.fillMaxSize().testTag("scene_picker_surface"), color = MaterialTheme.colorScheme.surface) {
            Scaffold(topBar = {
                TopAppBar(title = { Text("Scenes") }, navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close scenes") }
                })
            }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .imePadding().testTag("scene_gallery"),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        OutlinedTextField(query, { query = it }, label = { Text("Search scenes") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    groups.forEach { (group, scenes) ->
                        item(key = "group-$group") {
                            Text(group, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                        }
                        items(scenes, key = { it.id }) { scene ->
                            val selected = scene.id == selectedId
                            Card(onClick = { onSelect(scene) }, modifier = Modifier.fillMaxWidth()
                                .testTag("scene_${scene.id}")
                                .semantics { contentDescription = "${scene.name}, ${scene.group}${if (scene.animated) ", animated" else ""}" },
                                border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                Box(Modifier.fillMaxWidth().height(104.dp)) {
                                    SceneArtwork(scene, Modifier.fillMaxSize(), previewOnly = !selected)
                                }
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(scene.name, style = MaterialTheme.typography.titleSmall)
                                        if (scene.animated) Text("Animated", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Box(Modifier.size(24.dp).background(Color(scene.seedArgb), CircleShape))
                                    if (selected) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    if (groups.isEmpty()) item { Text("No scenes match your search.") }
                }
            }
        }
    }
}

@Composable
internal fun MainColorDialog(initialArgb: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initialArgb, it) } }
    var hue by rememberSaveable { mutableFloatStateOf(hsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(hsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(hsv[2]) }
    var input by rememberSaveable { mutableStateOf(String.format(Locale.ROOT, "#%06X", initialArgb and 0xFFFFFF)) }
    fun update(h: Float, s: Float, v: Float) {
        hue = h; saturation = s; brightness = v
        input = String.format(Locale.ROOT, "#%06X", Color.hsv(h, s, v).toArgb() and 0xFFFFFF)
    }
    val argb = MessengerPlusColorValue.fromHex(input)?.argb
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val preview = remember(argb, isDark) { seededColorScheme(argb ?: initialArgb, isDark) }
    AlertDialog(onDismissRequest = onDismiss,
        modifier = Modifier.testTag("main_color_dialog"), title = { Text("Main color") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorWheel(hue, saturation, brightness, { h, s -> update(h, s, brightness) },
                    Modifier.align(Alignment.CenterHorizontally), testTag = "main_color_wheel")
                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(brightness, { update(hue, saturation, it) },
                    modifier = Modifier.semantics { contentDescription = "Brightness" })
                OutlinedTextField(input, {
                    input = it.take(7)
                    MessengerPlusColorValue.fromHex(input)?.let { value ->
                        val next = FloatArray(3).also { values -> android.graphics.Color.colorToHSV(value.argb, values) }
                        hue = next[0]; saturation = next[1]; brightness = next[2]
                    }
                }, label = { Text("Hex color") }, supportingText = { Text("#RRGGBB") },
                    isError = argb == null, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("main_color_hex"))
                Surface(color = preview.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Palette preview", color = preview.onSurface, style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(preview.primary, preview.secondary, preview.tertiary, preview.primaryContainer).forEach {
                                Box(Modifier.size(32.dp).background(it, CircleShape))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { argb?.let(onSelect) }, enabled = argb != null,
            shapes = ButtonDefaults.shapes()) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } })
}
