@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.protocol.InkDrawing
import org.aralozkaya.msngr.protocol.InkPoint
import org.aralozkaya.msngr.protocol.InkStroke
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal const val EMOTICON_MODE_BUTTON_TAG = "emoticon_mode_button"
internal const val EMOTICON_LONG_PRESS_DOTS_TAG = "emoticon_long_press_dots"
internal const val EMOTICON_MENU_EMOTICONS_ITEM_TAG = "emoticon_menu_emoticons_item"
internal const val EMOTICON_MENU_HANDWRITING_ITEM_TAG = "emoticon_menu_handwriting_item"
internal const val HANDWRITING_MODE_BUTTON_TAG = "handwriting_mode_button"
internal const val HANDWRITING_TOOLBAR_TAG = "handwriting_floating_toolbar"
internal const val HANDWRITING_CANVAS_TAG = "handwriting_canvas"
internal const val HANDWRITING_PEN_BUTTON_TAG = "handwriting_pen_button"
internal const val HANDWRITING_ERASER_BUTTON_TAG = "handwriting_eraser_button"
internal const val HANDWRITING_COLOR_BUTTON_TAG = "handwriting_color_button"
internal const val HANDWRITING_GRID_BUTTON_TAG = "handwriting_grid_button"
internal const val HANDWRITING_UNDO_BUTTON_TAG = "handwriting_undo_button"
internal const val HANDWRITING_REDO_BUTTON_TAG = "handwriting_redo_button"
internal const val HANDWRITING_OVERFLOW_BUTTON_TAG = "handwriting_overflow_button"
internal const val HANDWRITING_SEND_BUTTON_TAG = "handwriting_send_button"
internal const val HANDWRITING_TYPE_BUTTON_TAG = "handwriting_type_mode_button"
internal const val INK_MESSAGE_CANVAS_TAG = "ink_message_canvas"

/** Pen widths from WLM 2009's Handwriting Pen Size submenu (`10`, `100`, `200` HIMETRIC). */
enum class WlmPenSize(val label: String, val widthHimetric: Int, val previewDp: Float) {
    Fine("Fine", InkStroke.FINE_PEN_WIDTH_HIMETRIC, 2f),
    Normal("Normal", InkStroke.NORMAL_PEN_WIDTH_HIMETRIC, 5f),
    Bold("Bold", InkStroke.BOLD_PEN_WIDTH_HIMETRIC, 9f),
}

/** Eraser modes from WLM 2009's Handwriting Eraser submenu (`212`, `500`, `501`, `502`). */
enum class WlmEraserMode(val label: String, val radiusPx: Float, val wholeStroke: Boolean) {
    Small("Small", 10f, wholeStroke = false),
    Big("Big", 24f, wholeStroke = false),
    Stroke("Stroke", 14f, wholeStroke = true),
}

/** Rule-line / grid spacing options from WLM 2009's Grid submenu (`10`, `30`, `50`, `0`). */
enum class WlmGridStyle(val label: String, val spacingPx: Int) {
    SmallGrid("Small grid", 10),
    MediumGrid("Medium grid", 30),
    LargeGrid("Large grid", 50),
    Blank("Blank", 0),
}

enum class WlmHandwritingTool {
    Pen,
    Eraser,
}

data class WlmInkColorOption(
    val name: String,
    val rgb: Int,
) {
    val composeColor: Color get() = Color(0xFF000000L or (rgb.toLong() and 0xFFFFFFL))
}

/** Exact 16-color WLM 2009 ISF protocol palette (`chat.xml` IDs `55510..55525`). */
val WLM_INK_COLORS: List<WlmInkColorOption> = listOf(
    WlmInkColorOption("Black", 0x000000),
    WlmInkColorOption("Gray", 0x404040),
    WlmInkColorOption("Silver", 0x808080),
    WlmInkColorOption("White", 0xFFFFFF),
    WlmInkColorOption("Navy", 0x000080),
    WlmInkColorOption("Blue", 0x0000FF),
    WlmInkColorOption("Aqua", 0x00FFFF),
    WlmInkColorOption("Teal", 0x008080),
    WlmInkColorOption("Maroon", 0x800000),
    WlmInkColorOption("Red", 0xFF0000),
    WlmInkColorOption("Fuchsia", 0xFF00FF),
    WlmInkColorOption("Purple", 0x800080),
    WlmInkColorOption("Green", 0x008000),
    WlmInkColorOption("Lime", 0x00FF00),
    WlmInkColorOption("Olive", 0x808000),
    WlmInkColorOption("Yellow", 0xFFFF00),
)

class HandwritingCanvasState {
    private val currentStrokes = mutableStateListOf<InkStroke>()
    private val undoStack = mutableStateListOf<List<InkStroke>>()
    private val redoStack = mutableStateListOf<List<InkStroke>>()

    var activeTool by mutableStateOf(WlmHandwritingTool.Pen)
    var penSize by mutableStateOf(WlmPenSize.Normal)
    var eraserMode by mutableStateOf(WlmEraserMode.Small)
    var gridStyle by mutableStateOf(WlmGridStyle.Blank)
    var selectedColorRgb by mutableIntStateOf(0x000000)
    var canvasWidthPx by mutableFloatStateOf(411f)
    var canvasHeightPx by mutableFloatStateOf(180f)

    val strokes: List<InkStroke> get() = currentStrokes
    val hasStrokes: Boolean get() = currentStrokes.isNotEmpty()
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun commitStroke(points: List<InkPoint>) {
        if (points.isEmpty()) return
        val normalizedPoints = if (points.size == 1) {
            listOf(points[0], InkPoint(points[0].x + 0.5f, points[0].y + 0.5f))
        } else {
            points
        }
        pushUndoSnapshot()
        currentStrokes.add(
            InkStroke(
                points = normalizedPoints,
                colorRgb = selectedColorRgb,
                widthHimetric = penSize.widthHimetric,
            ),
        )
    }

    fun applyEraserPath(points: List<InkPoint>) {
        if (points.isEmpty() || currentStrokes.isEmpty()) return
        val updated = eraseStrokesAlongPath(
            strokes = currentStrokes.toList(),
            eraserPoints = points,
            radiusPx = eraserMode.radiusPx,
            wholeStroke = eraserMode.wholeStroke,
        )
        if (updated != currentStrokes.toList()) {
            pushUndoSnapshot()
            currentStrokes.clear()
            currentStrokes.addAll(updated)
        }
    }

    fun clearAll() {
        if (currentStrokes.isNotEmpty()) {
            pushUndoSnapshot()
            currentStrokes.clear()
        }
        // WLM 2009's "Clear all" (cmd 502) resets the active tool back to Pen.
        activeTool = WlmHandwritingTool.Pen
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val previous = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(currentStrokes.toList())
        currentStrokes.clear()
        currentStrokes.addAll(previous)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(currentStrokes.toList())
        currentStrokes.clear()
        currentStrokes.addAll(next)
    }

    fun toInkDrawing(): InkDrawing = InkDrawing(
        canvasWidthPx = max(64f, canvasWidthPx),
        canvasHeightPx = max(64f, canvasHeightPx),
        strokes = currentStrokes.toList(),
    )

    fun resetAfterSend() {
        currentStrokes.clear()
        undoStack.clear()
        redoStack.clear()
        activeTool = WlmHandwritingTool.Pen
    }

    private fun pushUndoSnapshot() {
        undoStack.add(currentStrokes.toList())
        if (undoStack.size > 50) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
    }
}

@Composable
internal fun rememberHandwritingCanvasState(): HandwritingCanvasState =
    remember { HandwritingCanvasState() }

internal fun eraseStrokesAlongPath(
    strokes: List<InkStroke>,
    eraserPoints: List<InkPoint>,
    radiusPx: Float,
    wholeStroke: Boolean,
): List<InkStroke> {
    if (strokes.isEmpty() || eraserPoints.isEmpty()) return strokes
    val threshold = max(2f, radiusPx)
    if (wholeStroke) {
        return strokes.filterNot { stroke ->
            val strokeHitRadius = threshold + stroke.widthPx * 0.5f
            stroke.points.any { pt ->
                eraserPoints.any { ep -> hypot(pt.x - ep.x, pt.y - ep.y) <= strokeHitRadius }
            }
        }
    }

    val result = mutableListOf<InkStroke>()
    for (stroke in strokes) {
        val strokeHitRadius = threshold + stroke.widthPx * 0.5f
        // Subdivide long segments so fast strokes still split cleanly when erased in the middle.
        val densePoints = densifyStrokePoints(stroke.points, maxStepPx = 4f)
        var currentSegment = mutableListOf<InkPoint>()
        for (pt in densePoints) {
            val erased = eraserPoints.any { ep ->
                hypot(pt.x - ep.x, pt.y - ep.y) <= strokeHitRadius
            }
            if (erased) {
                if (currentSegment.isNotEmpty()) {
                    result += stroke.copy(points = normalizeSegment(currentSegment))
                    currentSegment = mutableListOf()
                }
            } else {
                currentSegment += pt
            }
        }
        if (currentSegment.isNotEmpty()) {
            result += stroke.copy(points = normalizeSegment(currentSegment))
        }
    }
    return result
}

private fun densifyStrokePoints(points: List<InkPoint>, maxStepPx: Float): List<InkPoint> {
    if (points.size <= 1) return points
    val output = ArrayList<InkPoint>(points.size * 2)
    output += points.first()
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        val dist = hypot(curr.x - prev.x, curr.y - prev.y)
        val steps = (dist / maxStepPx).toInt()
        if (steps > 1) {
            for (step in 1 until steps) {
                val t = step.toFloat() / steps.toFloat()
                output += InkPoint(
                    x = prev.x + (curr.x - prev.x) * t,
                    y = prev.y + (curr.y - prev.y) * t,
                )
            }
        }
        output += curr
    }
    return output
}

private fun normalizeSegment(points: List<InkPoint>): List<InkPoint> =
    if (points.size == 1) {
        listOf(points[0], InkPoint(points[0].x + 0.5f, points[0].y + 0.5f))
    } else {
        points
    }

private enum class HandwritingToolbarItem {
    TypeMode,
    Pen,
    Eraser,
    Undo,
    Redo,
    Color,
    Grid,
}

@Composable
private fun HandwritingSurface(
    modifier: Modifier,
    shape: Shape,
    content: @Composable () -> Unit,
) {
    // Ink is drawn on white paper, including inside elevated or dynamically colored surfaces.
    CompositionLocalProvider(LocalTonalElevationEnabled provides false) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = Color.White,
            contentColor = Color.DarkGray,
            border = BorderStroke(1.dp, Color.LightGray),
            content = content,
        )
    }
}

@Composable
internal fun HandwritingComposerPanel(
    state: HandwritingCanvasState,
    enabled: Boolean,
    onSendInk: (InkDrawing) -> Unit,
    onSwitchToTypeMode: () -> Unit,
    onStrokeActivity: () -> Unit = {},
    toolbarVisible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var showPenMenu by remember { mutableStateOf(false) }
    var showEraserMenu by remember { mutableStateOf(false) }
    var showColorMenu by remember { mutableStateOf(false) }
    var showGridMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val penMenuInteractionSource = remember { MutableInteractionSource() }
    val eraserMenuInteractionSource = remember { MutableInteractionSource() }
    val colorMenuInteractionSource = remember { MutableInteractionSource() }
    val gridMenuInteractionSource = remember { MutableInteractionSource() }
    val overflowMenuInteractionSource = remember { MutableInteractionSource() }
    var toolbarExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(toolbarVisible) {
        toolbarExpanded = toolbarVisible
        if (!toolbarVisible) {
            showPenMenu = false
            showEraserMenu = false
            showColorMenu = false
            showGridMenu = false
            showOverflowMenu = false
        }
    }
    val toolbarScale by animateFloatAsState(
        targetValue = if (toolbarExpanded) 1f else 0.85f,
        animationSpec = MotionScheme.expressive().fastSpatialSpec(),
        label = "handwritingToolbarScale",
    )
    val toolbarAlpha by animateFloatAsState(
        targetValue = if (toolbarExpanded) 1f else 0f,
        animationSpec = MotionScheme.expressive().fastEffectsSpec(),
        label = "handwritingToolbarAlpha",
    )
    val activeStrokePoints = remember { mutableStateListOf<InkPoint>() }
    val vibrantColors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()
    val selectedButtonColors = IconButtonDefaults.iconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    val unselectedButtonColors = IconButtonDefaults.iconButtonColors(
        containerColor = Color.Transparent,
        contentColor = vibrantColors.toolbarContentColor,
    )
    val selectedColorOption = remember(state.selectedColorRgb) {
        WLM_INK_COLORS.firstOrNull { it.rgb == state.selectedColorRgb } ?: WLM_INK_COLORS.first()
    }
    val gridLineColor = Color.LightGray

    @Composable
    fun PenSubmenuPopup() {
        DropdownMenuPopup(
            expanded = showPenMenu,
            onDismissRequest = { showPenMenu = false },
        ) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(0, 1),
                interactionSource = penMenuInteractionSource,
            ) {
                WlmPenSize.entries.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        shape = MenuDefaults.itemShape(index, WlmPenSize.entries.size).shape,
                        leadingIcon = {
                            Box(
                                modifier = Modifier.size(18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(option.previewDp.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurface),
                                )
                            }
                        },
                        trailingContent = if (state.penSize == option && state.activeTool == WlmHandwritingTool.Pen) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            state.penSize = option
                            state.activeTool = WlmHandwritingTool.Pen
                            showPenMenu = false
                        },
                    )
                }
            }
        }
    }

    @Composable
    fun EraserSubmenuPopup() {
        DropdownMenuPopup(
            expanded = showEraserMenu,
            onDismissRequest = { showEraserMenu = false },
        ) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(0, 2),
                interactionSource = eraserMenuInteractionSource,
            ) {
                WlmEraserMode.entries.forEachIndexed { index, mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        shape = if (index == 0) {
                            MenuDefaults.leadingItemShape
                        } else {
                            MenuDefaults.middleItemShape
                        },
                        trailingContent = if (state.activeTool == WlmHandwritingTool.Eraser && state.eraserMode == mode) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            state.eraserMode = mode
                            state.activeTool = WlmHandwritingTool.Eraser
                            showEraserMenu = false
                        },
                    )
                }
            }
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(1, 2),
                interactionSource = eraserMenuInteractionSource,
            ) {
                DropdownMenuItem(
                    text = { Text("Clear all") },
                    shape = MenuDefaults.trailingItemShape,
                    leadingIcon = {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    },
                    enabled = state.hasStrokes,
                    onClick = {
                        state.clearAll()
                        showEraserMenu = false
                    },
                )
            }
        }
    }

    @Composable
    fun ColorSubmenuPopup() {
        DropdownMenuPopup(
            expanded = showColorMenu,
            onDismissRequest = { showColorMenu = false },
        ) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(0, 1),
                interactionSource = colorMenuInteractionSource,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Ink color",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    WLM_INK_COLORS.chunked(4).forEach { rowColors ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowColors.forEach { option ->
                                val isSelected = state.selectedColorRgb == option.rgb
                                Button(
                                    onClick = {
                                        state.selectedColorRgb = option.rgb
                                        state.activeTool = WlmHandwritingTool.Pen
                                        showColorMenu = false
                                    },
                                    shapes = ButtonDefaults.shapes(
                                        shape = if (isSelected) ButtonDefaults.squareShape else ButtonDefaults.shape,
                                    ),
                                    colors = ButtonDefaults.buttonColors(containerColor = option.composeColor),
                                    border = BorderStroke(
                                        if (isSelected) 2.5.dp else 1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    ),
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(30.dp).semantics {
                                        contentDescription = "Ink color ${option.name}"
                                    },
                                ) {}
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun GridSubmenuPopup() {
        DropdownMenuPopup(
            expanded = showGridMenu,
            onDismissRequest = { showGridMenu = false },
        ) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(0, 1),
                interactionSource = gridMenuInteractionSource,
            ) {
                WlmGridStyle.entries.forEachIndexed { index, style ->
                    DropdownMenuItem(
                        text = { Text(style.label) },
                        shape = MenuDefaults.itemShape(index, WlmGridStyle.entries.size).shape,
                        trailingContent = if (state.gridStyle == style) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            state.gridStyle = style
                            showGridMenu = false
                        },
                    )
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HandwritingSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(184.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .testTag(HANDWRITING_CANVAS_TAG)
                        .semantics {
                            contentDescription = "Handwriting canvas"
                            stateDescription = "${state.strokes.size} strokes, ${state.penSize.label} pen, ${selectedColorOption.name}, ${state.gridStyle.label}"
                        }
                        .onSizeChanged { size ->
                            if (size.width > 0 && size.height > 0) {
                                state.canvasWidthPx = size.width.toFloat()
                                state.canvasHeightPx = size.height.toFloat()
                            }
                        }
                        .pointerInput(enabled, state.activeTool, state.penSize, state.eraserMode, state.selectedColorRgb) {
                            if (!enabled) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                activeStrokePoints.clear()
                                val startPoint = InkPoint(
                                    x = down.position.x.coerceIn(0f, size.width.toFloat()),
                                    y = down.position.y.coerceIn(0f, size.height.toFloat()),
                                )
                                activeStrokePoints.add(startPoint)
                                onStrokeActivity()
                                if (state.activeTool == WlmHandwritingTool.Eraser) {
                                    state.applyEraserPath(listOf(startPoint))
                                }

                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: event.changes.firstOrNull()
                                        ?: break
                                    change.consume()
                                    val pt = InkPoint(
                                        x = change.position.x.coerceIn(0f, size.width.toFloat()),
                                        y = change.position.y.coerceIn(0f, size.height.toFloat()),
                                    )
                                    val last = activeStrokePoints.lastOrNull()
                                    if (last == null || hypot(pt.x - last.x, pt.y - last.y) >= 1.5f) {
                                        activeStrokePoints.add(pt)
                                        if (state.activeTool == WlmHandwritingTool.Eraser) {
                                            state.applyEraserPath(
                                                if (last != null) listOf(last, pt) else listOf(pt),
                                            )
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                if (state.activeTool == WlmHandwritingTool.Pen && activeStrokePoints.isNotEmpty()) {
                                    state.commitStroke(activeStrokePoints.toList())
                                }
                                activeStrokePoints.clear()
                            }
                        },
                ) {
                    drawRuleGrid(gridStyle = state.gridStyle, gridColor = gridLineColor)
                    for (stroke in state.strokes) {
                        drawInkStroke(stroke = stroke)
                    }
                    if (state.activeTool == WlmHandwritingTool.Pen && activeStrokePoints.isNotEmpty()) {
                        drawInkStroke(
                            stroke = InkStroke(
                                points = activeStrokePoints,
                                colorRgb = state.selectedColorRgb,
                                widthHimetric = state.penSize.widthHimetric,
                            ),
                        )
                    }
                }

                if (!state.hasStrokes && activeStrokePoints.isEmpty()) {
                    Text(
                        text = "Draw handwriting message hereâ€¦",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }

        // Material 3 Expressive HorizontalFloatingToolbar (Vibrant) paired with a Vibrant FAB
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val allToolbarItems = HandwritingToolbarItem.entries
            // Reserve space for the paired FAB (56dp) + toolbar-to-FAB gap (8dp) + toolbar contentPadding (16dp).
            // Each Material 3 IconButton occupies a 48dp interactive slot.
            val buttonSlotWidth = 48.dp
            val availableForToolbarButtons = (maxWidth - 80.dp).coerceAtLeast(buttonSlotWidth)
            val maxFittingSlots = (availableForToolbarButtons / buttonSlotWidth).toInt().coerceAtLeast(1)
            val hasOverflow = maxFittingSlots < allToolbarItems.size
            val visibleItems = if (hasOverflow) {
                allToolbarItems.take((maxFittingSlots - 1).coerceAtLeast(0))
            } else {
                allToolbarItems
            }
            val overflowItems = if (hasOverflow) {
                allToolbarItems.drop(visibleItems.size)
            } else {
                emptyList()
            }
            val sendEnabled = enabled && state.hasStrokes

            HorizontalFloatingToolbar(
                expanded = toolbarExpanded,
                colors = vibrantColors,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = toolbarScale
                        scaleY = toolbarScale
                        alpha = toolbarAlpha
                    }
                    .testTag(HANDWRITING_TOOLBAR_TAG),
                floatingActionButton = {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(
                        shape = FloatingActionButtonDefaults.shape,
                        onClick = {
                            if (sendEnabled) {
                                onSendInk(state.toInkDrawing())
                                state.resetAfterSend()
                            }
                        },
                        containerColor = vibrantColors.fabContainerColor,
                        contentColor = vibrantColors.fabContentColor,
                        modifier = Modifier
                            .testTag(HANDWRITING_SEND_BUTTON_TAG)
                            .semantics {
                                if (!sendEnabled) {
                                    disabled()
                                }
                            },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send handwriting",
                        )
                    }
                },
            ) {
                visibleItems.forEach { item ->
                    when (item) {
                        HandwritingToolbarItem.TypeMode -> {
                            IconButton(
                                onClick = onSwitchToTypeMode,
                                colors = unselectedButtonColors,
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag(HANDWRITING_TYPE_BUTTON_TAG),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Keyboard,
                                    contentDescription = "Type mode",
                                )
                            }
                        }

                        HandwritingToolbarItem.Pen -> {
                            Box {
                                IconButton(
                                    onClick = {
                                        if (state.activeTool != WlmHandwritingTool.Pen) {
                                            state.activeTool = WlmHandwritingTool.Pen
                                        } else {
                                            showPenMenu = true
                                        }
                                    },
                                    shapes = IconButtonDefaults.shapes(),
                                    enabled = enabled,
                                    colors = if (state.activeTool == WlmHandwritingTool.Pen) {
                                        selectedButtonColors
                                    } else {
                                        unselectedButtonColors
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag(HANDWRITING_PEN_BUTTON_TAG),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Draw,
                                        contentDescription = "Pen (${state.penSize.label})",
                                    )
                                }
                                PenSubmenuPopup()
                            }
                        }

                        HandwritingToolbarItem.Eraser -> {
                            Box {
                                IconButton(
                                    onClick = { showEraserMenu = true },
                                    shapes = IconButtonDefaults.shapes(),
                                    enabled = enabled,
                                    colors = if (state.activeTool == WlmHandwritingTool.Eraser) {
                                        selectedButtonColors
                                    } else {
                                        unselectedButtonColors
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag(HANDWRITING_ERASER_BUTTON_TAG),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoFixNormal,
                                        contentDescription = if (state.activeTool == WlmHandwritingTool.Eraser) {
                                            "Eraser (${state.eraserMode.label}, active)"
                                        } else {
                                            "Eraser (${state.eraserMode.label})"
                                        },
                                    )
                                }
                                EraserSubmenuPopup()
                            }
                        }

                        HandwritingToolbarItem.Undo -> {
                            IconButton(
                                onClick = state::undo,
                                enabled = enabled && state.canUndo,
                                colors = unselectedButtonColors,
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag(HANDWRITING_UNDO_BUTTON_TAG),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = "Undo stroke",
                                )
                            }
                        }

                        HandwritingToolbarItem.Redo -> {
                            IconButton(
                                onClick = state::redo,
                                enabled = enabled && state.canRedo,
                                colors = unselectedButtonColors,
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag(HANDWRITING_REDO_BUTTON_TAG),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Redo,
                                    contentDescription = "Redo stroke",
                                )
                            }
                        }

                        HandwritingToolbarItem.Color -> {
                            Box {
                                IconButton(
                                    onClick = { showColorMenu = true },
                                    enabled = enabled,
                                    colors = unselectedButtonColors,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag(HANDWRITING_COLOR_BUTTON_TAG),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Palette,
                                            contentDescription = "Ink color (${selectedColorOption.name})",
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(selectedColorOption.composeColor)
                                                .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                        )
                                    }
                                }
                                ColorSubmenuPopup()
                            }
                        }

                        HandwritingToolbarItem.Grid -> {
                            Box {
                                IconButton(
                                    onClick = { showGridMenu = true },
                                    enabled = enabled,
                                    colors = unselectedButtonColors,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag(HANDWRITING_GRID_BUTTON_TAG),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GridOn,
                                        contentDescription = "Grid (${state.gridStyle.label})",
                                    )
                                }
                                GridSubmenuPopup()
                            }
                        }
                    }
                }

                if (overflowItems.isNotEmpty()) {
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            colors = unselectedButtonColors,
                            modifier = Modifier
                                .size(40.dp)
                                .testTag(HANDWRITING_OVERFLOW_BUTTON_TAG),
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More handwriting tools",
                            )
                        }
                        DropdownMenuPopup(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuGroup(
                                shapes = MenuDefaults.groupShape(0, 1),
                                interactionSource = overflowMenuInteractionSource,
                            ) {
                                overflowItems.forEachIndexed { index, item ->
                                    val itemShape = MenuDefaults.itemShape(index, overflowItems.size).shape
                                    when (item) {
                                        HandwritingToolbarItem.TypeMode -> {
                                            DropdownMenuItem(
                                                text = { Text("Type mode") },
                                                leadingIcon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                                                shape = itemShape,
                                                modifier = Modifier.testTag(HANDWRITING_TYPE_BUTTON_TAG),
                                                onClick = {
                                                    showOverflowMenu = false
                                                    onSwitchToTypeMode()
                                                },
                                            )
                                        }

                                        HandwritingToolbarItem.Pen -> {
                                            DropdownMenuItem(
                                                text = { Text("Pen (${state.penSize.label})") },
                                                leadingIcon = { Icon(Icons.Default.Draw, contentDescription = null) },
                                                shape = itemShape,
                                                enabled = enabled,
                                                modifier = Modifier.testTag(HANDWRITING_PEN_BUTTON_TAG),
                                                onClick = {
                                                    showOverflowMenu = false
                                                    showPenMenu = true
                                                },
                                            )
                                        }

                                        HandwritingToolbarItem.Eraser -> {
                                            DropdownMenuItem(
                                                text = { Text("Eraser (${state.eraserMode.label})") },
                                                leadingIcon = { Icon(Icons.Default.AutoFixNormal, contentDescription = null) },
                                                shape = itemShape,
                                                enabled = enabled,
                                                modifier = Modifier.testTag(HANDWRITING_ERASER_BUTTON_TAG),
                                                onClick = {
                                                    showOverflowMenu = false
                                                    showEraserMenu = true
                                                },
                                            )
                                        }

                                        HandwritingToolbarItem.Undo -> {
                                            DropdownMenuItem(
                                                text = { Text("Undo stroke") },
                                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) },
                                                shape = itemShape,
                                                enabled = enabled && state.canUndo,
                                                modifier = Modifier.testTag(HANDWRITING_UNDO_BUTTON_TAG),
                                                onClick = {
                                                    showOverflowMenu = false
                                                    state.undo()
                                                },
                                            )
                                        }

                                        HandwritingToolbarItem.Redo -> {
                                            DropdownMenuItem(
                                                text = { Text("Redo stroke") },
                                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = null) },
                                                shape = itemShape,
                                                enabled = enabled && state.canRedo,
                                                modifier = Modifier.testTag(HANDWRITING_REDO_BUTTON_TAG),
                                                onClick = {
                                                    showOverflowMenu = false
                                                    state.redo()
                                                },
                                            )
                                        }

                                        HandwritingToolbarItem.Color -> {
                                            DropdownMenuItem(
                                                text = { Text("Ink color (${selectedColorOption.name})") },
                                                leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                                                shape = itemShape,
                                                enabled = enabled,
                                                modifier = Modifier.testTag(HANDWRITING_COLOR_BUTTON_TAG),
                                                onClick = {
                                                    showOverflowMenu = false
                                                    showColorMenu = true
                                                },
                                            )
                                        }

                                        HandwritingToolbarItem.Grid -> {
                                            DropdownMenuItem(
                                                text = { Text("Grid (${state.gridStyle.label})") },
                                                leadingIcon = { Icon(Icons.Default.GridOn, contentDescription = null) },
                                                shape = itemShape,
                                                enabled = enabled,
                                                modifier = Modifier.testTag(HANDWRITING_GRID_BUTTON_TAG),
                                                onClick = {
                                                    showOverflowMenu = false
                                                    showGridMenu = true
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (HandwritingToolbarItem.Pen in overflowItems) PenSubmenuPopup()
                        if (HandwritingToolbarItem.Eraser in overflowItems) EraserSubmenuPopup()
                        if (HandwritingToolbarItem.Color in overflowItems) ColorSubmenuPopup()
                        if (HandwritingToolbarItem.Grid in overflowItems) GridSubmenuPopup()
                    }
                }
            }
        }
    }
}

@Composable
internal fun HandwritingMessageCanvas(
    drawing: InkDrawing,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val bounds = remember(drawing) { computeDrawingBounds(drawing) }
    val aspect = remember(bounds) {
        (bounds.width / max(1f, bounds.height)).coerceIn(1.1f, 4.5f)
    }
    val preferredWidthDp = with(density) {
        (bounds.width.toDp() + 24.dp).coerceIn(140.dp, 280.dp)
    }

    HandwritingSurface(
        modifier = modifier
            .widthIn(min = 130.dp, max = preferredWidthDp)
            .fillMaxWidth()
            .aspectRatio(aspect, matchHeightConstraintsFirst = false),
        shape = MaterialTheme.shapes.medium,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
                .testTag(INK_MESSAGE_CANVAS_TAG)
                .semantics {
                    contentDescription = "Handwriting message (${drawing.strokes.size} strokes)"
                },
        ) {
            val scaleX = size.width / max(1f, bounds.width)
            val scaleY = size.height / max(1f, bounds.height)
            val scale = min(scaleX, scaleY)
            val offsetX = (size.width - bounds.width * scale) * 0.5f - bounds.minX * scale
            val offsetY = (size.height - bounds.height * scale) * 0.5f - bounds.minY * scale

            for (stroke in drawing.strokes) {
                drawInkStroke(
                    stroke = stroke,
                    scale = scale,
                    offsetX = offsetX,
                    offsetY = offsetY,
                )
            }
        }
    }
}

private data class InkBounds(
    val minX: Float,
    val minY: Float,
    val width: Float,
    val height: Float,
)

private fun computeDrawingBounds(drawing: InkDrawing): InkBounds {
    val nonEmpty = drawing.strokes.filter { it.points.isNotEmpty() }
    if (nonEmpty.isEmpty()) {
        return InkBounds(0f, 0f, max(64f, drawing.canvasWidthPx), max(48f, drawing.canvasHeightPx))
    }
    val pad = nonEmpty.maxOf { max(4f, it.widthPx) }
    val minX = nonEmpty.minOf { s -> s.points.minOf(InkPoint::x) } - pad
    val minY = nonEmpty.minOf { s -> s.points.minOf(InkPoint::y) } - pad
    val maxX = nonEmpty.maxOf { s -> s.points.maxOf(InkPoint::x) } + pad
    val maxY = nonEmpty.maxOf { s -> s.points.maxOf(InkPoint::y) } + pad
    return InkBounds(
        minX = minX,
        minY = minY,
        width = max(48f, maxX - minX),
        height = max(36f, maxY - minY),
    )
}

private fun DrawScope.drawRuleGrid(
    gridStyle: WlmGridStyle,
    gridColor: Color,
) {
    val step = gridStyle.spacingPx
    if (step <= 0) return
    var x = step.toFloat()
    while (x < size.width) {
        drawLine(
            color = gridColor,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f,
        )
        x += step
    }
    var y = step.toFloat()
    while (y < size.height) {
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
        y += step
    }
}

private fun DrawScope.drawInkStroke(
    stroke: InkStroke,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
) {
    val points = stroke.points
    if (points.isEmpty()) return
    val strokeColor = Color(0xFF000000L or (stroke.colorRgb.toLong() and 0xFFFFFFL))
    val widthPx = max(1.6f, stroke.widthPx * scale)

    if (points.size == 1) {
        val cx = points[0].x * scale + offsetX
        val cy = points[0].y * scale + offsetY
        drawCircle(
            color = strokeColor,
            radius = widthPx * 0.5f,
            center = Offset(cx, cy),
        )
        return
    }

    val path = Path()
    val x0 = points[0].x * scale + offsetX
    val y0 = points[0].y * scale + offsetY
    path.moveTo(x0, y0)

    if (points.size == 2) {
        val x1 = points[1].x * scale + offsetX
        val y1 = points[1].y * scale + offsetY
        path.lineTo(x1, y1)
    } else {
        for (i in 1 until points.size - 1) {
            val currX = points[i].x * scale + offsetX
            val currY = points[i].y * scale + offsetY
            val nextX = points[i + 1].x * scale + offsetX
            val nextY = points[i + 1].y * scale + offsetY
            val midX = (currX + nextX) * 0.5f
            val midY = (currY + nextY) * 0.5f
            path.quadraticTo(currX, currY, midX, midY)
        }
        val last = points.last()
        path.lineTo(last.x * scale + offsetX, last.y * scale + offsetY)
    }

    drawPath(
        path = path,
        color = strokeColor,
        style = Stroke(
            width = widthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}
