@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package org.aralozkaya.msngr.pages.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Sheet height used until the software keyboard has been measured once in this orientation. */
internal val DefaultKeyboardSheetHeight: Dp = 290.dp

/**
 * Shorter IME insets come from compact toolbars, such as the suggestion strip shown with a
 * hardware keyboard. They neither size the sheet nor are remembered as the keyboard's height.
 */
internal val MinKeyboardHeight: Dp = 160.dp

internal val LocalKeyboardSheetHost = staticCompositionLocalOf<KeyboardSheetHostState?> { null }

/**
 * Hosts non-modal sheets that share the software keyboard's slot at the bottom of the window.
 *
 * The scaffold ignores every window inset: the sheet's background starts at the bottom of the
 * display, while its content is padded by the system bar and display cutout insets. Page content
 * stays full-screen behind the sheet and can union [keyboardSheetWindowInsets] with its bottom
 * insets so controls or scrollable content settle at `max(keyboard, sheet)`, keeping them still
 * while one replaces the other:
 *
 * - Opening a sheet over the keyboard places it in the keyboard's exact slot without animation,
 *   then closes the keyboard, revealing the sheet as the keyboard slides away.
 * - A keyboard opened by an input outside the sheet covers it, and the sheet is removed only
 *   after that keyboard animation has finished.
 * - An input inside the sheet lets both coexist: the sheet's content is padded above the
 *   keyboard, and the page content is padded along with it.
 *
 * The sheet's height matches the keyboard's last measured height (persisted per orientation),
 * or [DefaultKeyboardSheetHeight] before the keyboard has been seen. That height includes the
 * sheet's drag handle, which dismisses the sheet when dragged down or tapped. Sheets that opt
 * in with `expandable` can also be dragged up to twice that height.
 * Declare sheets anywhere below this scaffold with [KeyboardSheet].
 */
@Composable
fun KeyboardSheetScaffold(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val orientation = LocalConfiguration.current.orientation
    val baseMotionScheme = MaterialTheme.motionScheme
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val systemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val insets = remember { KeyboardSheetInsets(ime = ime, imeTarget = imeTarget, system = systemInsets) }
    // PartiallyExpanded is the keyboard slot of an expandable sheet; Expanded is its full height.
    // A sheet that cannot expand has no partial anchor, so it opens at Expanded.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded),
    )
    val host = remember(sheetState) { KeyboardSheetHostState(sheetState, insets) }
    val heightStore = remember(context, orientation) { KeyboardHeightStore(context, orientation) }
    // Material reads the sheet's show/hide motion from the theme; this lets a handoff snap.
    val sheetMotionScheme = remember(baseMotionScheme, host) {
        KeyboardSheetMotionScheme(baseMotionScheme) { host.instantMotion }
    }

    LaunchedEffect(host, density, heightStore) {
        host.runTransitions(
            density = density,
            storedHeightPx = { heightStore.heightPx(density) },
            hideKeyboard = view::hideSoftwareKeyboard,
        )
    }
    LaunchedEffect(host, density, heightStore, windowInfo) {
        host.observeKeyboard(density, windowInfo) { heightStore.save(it, density) }
    }

    CompositionLocalProvider(LocalKeyboardSheetHost provides host) {
        MaterialTheme(motionScheme = sheetMotionScheme) {
            val entry = host.displayed
            val sheetColor = entry?.containerColor ?: Color.Transparent
            BottomSheetScaffold(
                sheetContent = {
                    val instant = host.instantMotion
                    // Runs in the same apply as Material's motion update for the sheet.
                    SideEffect { host.appliedInstantMotion = instant }
                    if (entry != null) {
                        MaterialTheme(motionScheme = baseMotionScheme) {
                            KeyboardSheetContent(host, entry)
                        }
                    }
                },
                modifier = Modifier.layout { measurable, constraints ->
                    if (constraints.hasBoundedHeight) host.layoutHeightPx = constraints.maxHeight
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
                scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState),
                sheetPeekHeight = with(density) { host.peekHeightPx(this).toDp() },
                sheetMaxWidth = Dp.Unspecified,
                sheetShape = entry?.shape ?: RectangleShape,
                sheetContainerColor = sheetColor,
                sheetContentColor = contentColorFor(sheetColor),
                sheetShadowElevation = 0.dp,
                sheetDragHandle = if (entry != null) {
                    {
                        // Measured so the handle takes its space from the keyboard's slot.
                        Box(
                            Modifier.layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                host.handleHeightPx = placeable.height
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                            },
                        ) {
                            BottomSheetDefaults.DragHandle()
                        }
                    }
                } else {
                    null
                },
                // A sheet sharing the screen with its own keyboard stays put.
                sheetSwipeEnabled = host.requested != null && !host.contentFocused,
                containerColor = Color.Transparent,
                contentColor = LocalContentColor.current,
            ) {
                MaterialTheme(motionScheme = baseMotionScheme) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        propagateMinConstraints = true,
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

/**
 * Bottom window insets equal to the visible height of the active [KeyboardSheet], or zero when no
 * sheet is shown. Unioning this with `WindowInsets.safeDrawing` (or `WindowInsets.ime`) settles
 * bottom controls and scrollable content at `max(keyboard, sheet)`, keeping them still while one
 * replaces the other while the page itself remains full-screen behind the sheet.
 */
@Composable
internal fun keyboardSheetWindowInsets(): WindowInsets {
    val host = LocalKeyboardSheetHost.current ?: return ZeroWindowInsets
    return remember(host) { SheetReservedInsets(host) }
}

private val ZeroWindowInsets = WindowInsets(0, 0, 0, 0)

/** Whether the software keyboard is currently animating upward toward a non-zero height. */
@Composable
internal fun isKeyboardRising(): Boolean {
    val density = LocalDensity.current
    val host = LocalKeyboardSheetHost.current
    if (host != null) return host.isKeyboardRising(density)
    val target = WindowInsets.imeAnimationTarget.getBottom(density)
    return target > 0 && WindowInsets.ime.getBottom(density) < target
}

/**
 * Declares a sheet in the nearest [KeyboardSheetScaffold]. Only one sheet is shown at a time;
 * showing another replaces this one and calls [onDismissRequest]. The scaffold also calls
 * [onDismissRequest] when Back is pressed, the sheet is dragged down or its handle is tapped,
 * or a keyboard opened outside the sheet covers it. [content] is placed below the drag handle
 * in a column with a fixed height and may use weights. Does nothing outside a scaffold.
 *
 * When [expandable], the sheet opens at the keyboard's height and can be dragged up to twice
 * that height; [content] is sized to the visible part. Back collapses it before dismissing.
 *
 * When [keepOpenOnInputTap], tapping an input field outside the sheet keeps the sheet open
 * instead of opening the software keyboard and closing the sheet.
 */
@Composable
internal fun KeyboardSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    expandable: Boolean = false,
    keepOpenOnInputTap: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalKeyboardSheetHost.current ?: return
    val entry = remember(host) { KeyboardSheetEntry() }
    SideEffect {
        entry.content = content
        entry.onDismissRequest = onDismissRequest
        entry.expandable = expandable
        entry.keepOpenOnInputTap = keepOpenOnInputTap
        entry.containerColor = containerColor
        entry.shape = shape
    }
    DisposableEffect(host, entry, visible) {
        if (visible) host.request(entry) else host.release(entry)
        onDispose { host.release(entry) }
    }
}

/**
 * A [KeyboardSheet] styled for picker panels such as the color sheet and emoticon panel, with
 * a padded container, an optional pinned [header] and [footer], and a scrollable body.
 */
@Composable
internal fun KeyboardPickerSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    paneTitle: String,
    testTag: String,
    expandable: Boolean = false,
    keepOpenOnInputTap: Boolean = false,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    KeyboardSheet(
        visible = visible,
        onDismissRequest = onDismissRequest,
        expandable = expandable,
        keepOpenOnInputTap = keepOpenOnInputTap,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .testTag(testTag)
                .semantics { this.paneTitle = paneTitle }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header?.invoke(this)
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
                Spacer(Modifier.size(8.dp))
            }
            footer?.invoke(this)
        }
    }
}

/** A fixed-column grid of [48.dp] tiles inside a [KeyboardPickerSheet]. */
@Composable
internal fun <T> KeyboardSheetTileGrid(
    items: List<T>,
    columns: Int = 6,
    tile: @Composable (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                rowItems.forEach { item -> tile(item) }
                repeat(columns - rowItems.size) { Spacer(Modifier.size(48.dp)) }
            }
        }
    }
}

/** A [48.dp] interactive tile used in [KeyboardSheetTileGrid]. */
@Composable
internal fun KeyboardSheetTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    isSelected: Boolean? = null,
    color: Color = Color.Transparent,
    contentColor: Color = LocalContentColor.current,
    border: BorderStroke? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp).semantics {
            if (description != null) contentDescription = description
            if (isSelected != null) selected = isSelected
            role = Role.Button
        },
        shape = MaterialTheme.shapes.small,
        color = color,
        contentColor = contentColor,
        border = border,
    ) {
        Box(contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
private fun KeyboardSheetContent(host: KeyboardSheetHostState, entry: KeyboardSheetEntry) {
    val scope = rememberCoroutineScope()
    // Registered after page handlers, so Back closes the sheet before navigating.
    BackHandler(enabled = host.requested === entry) {
        if (host.isExpandedBeyondKeyboard) scope.launch { host.collapse() } else host.dismiss()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { host.contentFocused = it.hasFocus }
            .layout { measurable, constraints ->
                // The handle above this column is part of the sheet's height.
                val height = host.sheetLayoutHeight(this) - host.handleHeightPx
                host.measuredSheetHeightPx = height + host.handleHeightPx
                val placeable = measurable.measure(
                    constraints.copy(minHeight = 0, maxHeight = height),
                )
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            .windowInsetsPadding(host.insets.system.only(WindowInsetsSides.Horizontal)),
    ) {
        // Inputs inside the sheet belong to the sheet itself and may still open the keyboard.
        CompositionLocalProvider(LocalKeyboardSheetHost provides null) {
            Column(
                modifier = Modifier.layout { measurable, constraints ->
                    // Fit the visible part, so nothing hides below the display while partially open.
                    val contentHeight = host.visibleContentHeight(this)
                    val placeable = measurable.measure(
                        constraints.copy(minHeight = contentHeight, maxHeight = contentHeight),
                    )
                    layout(placeable.width, contentHeight) { placeable.place(0, 0) }
                },
                content = entry.content,
            )
        }
    }
}

@Stable
internal class KeyboardSheetEntry {
    var content: @Composable ColumnScope.() -> Unit by mutableStateOf({})
    var onDismissRequest: () -> Unit = {}
    var expandable by mutableStateOf(false)
    var keepOpenOnInputTap by mutableStateOf(false)
    var containerColor by mutableStateOf(Color.Unspecified)
    var shape: Shape by mutableStateOf(RectangleShape)
}

internal class KeyboardSheetInsets(
    val ime: WindowInsets,
    val imeTarget: WindowInsets,
    /** Everything the sheet's content avoids except the keyboard. */
    val system: WindowInsets,
)

@Stable
internal class KeyboardSheetHostState(
    private val sheetState: SheetState,
    internal val insets: KeyboardSheetInsets,
) {
    /** The sheet callers currently want shown. */
    internal var requested by mutableStateOf<KeyboardSheetEntry?>(null)
        private set

    /** The sheet composed in the scaffold; outlives [requested] while hiding. */
    internal var displayed by mutableStateOf<KeyboardSheetEntry?>(null)
        private set

    /** The keyboard-slot height, including the system bar area behind it. */
    private var sheetHeightPx by mutableIntStateOf(0)
    internal var layoutHeightPx by mutableIntStateOf(0)
    internal var handleHeightPx by mutableIntStateOf(0)
    internal var contentFocused by mutableStateOf(false)
    internal var instantMotion by mutableStateOf(false)
        private set
    internal var appliedInstantMotion by mutableStateOf(false)
    private var hideInstantly = false

    /** Whether the sheet has settled open, so a hidden state can only come from the user. */
    private var settledOpen by mutableStateOf(false)

    /** Limits an expandable sheet's growth while it is swapped in; see [swapToExpandable]. */
    private var expansionCapPx by mutableStateOf<Int?>(null)

    /** The sheet height from the latest layout, which is when Material updates its anchors. */
    internal var measuredSheetHeightPx by mutableIntStateOf(0)

    /** Whether the keyboard has been closed since the sheet was requested. */
    private var keyboardReleased = false

    val keepOpenOnInputTap: Boolean
        get() = requested?.keepOpenOnInputTap == true

    fun request(entry: KeyboardSheetEntry) {
        val previous = requested
        if (previous === entry) return
        requested = entry
        previous?.onDismissRequest?.invoke()
    }

    fun release(entry: KeyboardSheetEntry) {
        if (requested !== entry) return
        hideInstantly = false
        requested = null
    }

    fun dismiss(instant: Boolean = false) {
        val entry = requested ?: return
        hideInstantly = instant
        requested = null
        entry.onDismissRequest()
    }

    internal fun sheetBottomInset(density: Density): Int {
        val systemBottom = insets.system.getBottom(density)
        return if (contentFocused) max(systemBottom, insets.ime.getBottom(density)) else systemBottom
    }

    /** The keyboard-slot height including the handle, from the bottom of the display. */
    private fun sheetBaseHeight(density: Density): Int {
        val chrome = sheetBottomInset(density) + handleHeightPx
        val available = (layoutHeightPx - chrome).coerceAtLeast(0)
        val contentHeight = (sheetHeightPx - insets.system.getBottom(density) - handleHeightPx)
            .coerceIn(0, available)
        return contentHeight + chrome
    }

    /**
     * How far an expandable sheet can grow past its base: up to the keyboard slot's height
     * again, below the status bar.
     */
    private fun sheetExtraHeight(density: Density): Int {
        if (displayed?.expandable != true) return 0
        val room = layoutHeightPx - insets.system.getTop(density) - sheetBaseHeight(density)
        return minOf(sheetHeightPx, room, expansionCapPx ?: Int.MAX_VALUE).coerceAtLeast(0)
    }

    /** The sheet's full height, including any expansion. */
    internal fun sheetLayoutHeight(density: Density): Int =
        sheetBaseHeight(density) + sheetExtraHeight(density)

    /** Material's partial stop sits at the keyboard slot only while the sheet can expand. */
    internal fun peekHeightPx(density: Density): Int =
        if (sheetExtraHeight(density) > 0) sheetBaseHeight(density) else 0

    /** Content fits the visible part of the sheet, keeping its base size while sliding in or out. */
    internal fun visibleContentHeight(density: Density): Int {
        val base = sheetBaseHeight(density)
        val visible = visibleSheetHeight(density).coerceIn(base, base + sheetExtraHeight(density))
        return (visible - handleHeightPx - sheetBottomInset(density)).coerceAtLeast(0)
    }

    internal val isExpandedBeyondKeyboard: Boolean
        get() = sheetState.currentValue == SheetValue.Expanded && sheetState.hasPartiallyExpandedState

    internal suspend fun collapse() = sheetState.partialExpand()

    /** The visible part of the sheet. Ignores spring overshoot so page content stays still. */
    internal fun visibleSheetHeight(density: Density): Int {
        if (displayed == null) return 0
        val offset = try {
            sheetState.requireOffset()
        } catch (_: IllegalStateException) {
            return 0
        }
        return (layoutHeightPx - offset.roundToInt()).coerceIn(0, sheetLayoutHeight(density))
    }

    internal fun isKeyboardRising(density: Density): Boolean {
        val target = insets.imeTarget.getBottom(density)
        return target > 0 && insets.ime.getBottom(density) < target
    }

    /** Waits until Material's anchors have been laid out for the displayed sheet's height. */
    private suspend fun awaitAnchors(density: Density) {
        snapshotFlow {
            measuredSheetHeightPx == sheetLayoutHeight(density) &&
                sheetState.hasExpandedState &&
                sheetState.hasPartiallyExpandedState == (peekHeightPx(density) > 0)
        }.first { it }
    }

    /**
     * Material keeps targeting Expanded when anchors change, which would snap an open sheet to
     * the full height of a newly displayed expandable one. Grow by a single pixel first, settle
     * at the partial stop (the keyboard slot), and only then allow the full expansion.
     */
    private suspend fun swapToExpandable(target: KeyboardSheetEntry, density: Density) {
        expansionCapPx = 1
        try {
            displayed = target
            awaitAnchors(density)
            sheetState.partialExpand()
        } finally {
            expansionCapPx = null
        }
    }

    /**
     * Without a partial stop, Material treats a sheet resting at PartiallyExpanded as peeking
     * from the bottom of the display. Before a sheet that cannot expand replaces an expandable
     * one, settle the latter at Expanded at the keyboard slot: collapse it, then cap its growth
     * to a single pixel and expand into that.
     */
    private suspend fun swapToFixed(target: KeyboardSheetEntry, density: Density) {
        if (isExpandedBeyondKeyboard) sheetState.partialExpand()
        expansionCapPx = 1
        try {
            awaitAnchors(density)
            sheetState.expand()
            displayed = target
            awaitAnchors(density)
        } finally {
            expansionCapPx = null
        }
    }

    internal suspend fun runTransitions(
        density: Density,
        storedHeightPx: () -> Int,
        hideKeyboard: () -> Unit,
    ) = coroutineScope {
        // Dragging the sheet down or tapping its handle hides it through Material's state.
        launch {
            snapshotFlow {
                settledOpen && requested != null &&
                    sheetState.currentValue == SheetValue.Hidden && !sheetState.isAnimationRunning
            }.collect { hiddenByUser -> if (hiddenByUser) dismiss(instant = true) }
        }
        snapshotFlow { requested }.collectLatest { target ->
            settledOpen = false
            if (target == null) {
                if (displayed != null) {
                    if (hideInstantly) withInstantMotion { sheetState.hide() } else sheetState.hide()
                    displayed = null
                    measuredSheetHeightPx = 0
                    contentFocused = false
                }
                return@collectLatest
            }
            val previous = displayed
            if (previous != null) {
                // Replace the content in place at the keyboard slot, recovering from an
                // interrupted hide.
                when {
                    target.expandable && !previous.expandable &&
                        sheetState.currentValue == SheetValue.Expanded ->
                        swapToExpandable(target, density)
                    !target.expandable && previous.expandable -> swapToFixed(target, density)
                    else -> {
                        displayed = target
                        awaitAnchors(density)
                    }
                }
                sheetState.show()
                settledOpen = true
                return@collectLatest
            }
            val keyboardHeight = insets.imeTarget.getBottom(density)
            val replacesKeyboard = keyboardHeight >= with(density) { MinKeyboardHeight.roundToPx() }
            sheetHeightPx = if (replacesKeyboard) keyboardHeight else storedHeightPx()
            displayed = target
            // An expandable sheet opens at its partial stop, the keyboard slot.
            awaitAnchors(density)
            if (replacesKeyboard) {
                // Occupy the keyboard's slot before it starts closing.
                withInstantMotion { sheetState.show() }
                hideKeyboard()
            } else {
                // A compact IME toolbar still closes, but cannot size the sheet.
                if (keyboardHeight > 0) hideKeyboard()
                sheetState.show()
            }
            settledOpen = true
        }
    }

    internal suspend fun observeKeyboard(
        density: Density,
        windowInfo: WindowInfo,
        onKeyboardMeasured: (Int) -> Unit,
    ) {
        snapshotFlow {
            KeyboardFrame(
                height = insets.ime.getBottom(density),
                targetHeight = insets.imeTarget.getBottom(density),
                // A dialog's keyboard belongs to its own window and must not affect the sheet.
                windowFocused = windowInfo.isWindowFocused,
                sheetFocused = contentFocused,
                entry = requested,
            )
        }.collect { frame ->
            val shown = frame.targetHeight > 0
            val settled = frame.height == frame.targetHeight
            val fullKeyboard = frame.height >= with(density) { MinKeyboardHeight.roundToPx() }
            if (frame.windowFocused && shown && settled && fullKeyboard) onKeyboardMeasured(frame.height)
            when {
                frame.entry == null -> keyboardReleased = false
                !shown -> keyboardReleased = true
                // Covered by a keyboard opened outside the sheet: remove it once that settles.
                keyboardReleased && settled && frame.windowFocused && !frame.sheetFocused ->
                    dismiss(instant = frame.height >= visibleSheetHeight(density))
            }
        }
    }

    private suspend fun withInstantMotion(block: suspend () -> Unit) {
        instantMotion = true
        try {
            snapshotFlow { appliedInstantMotion }.first { it }
            block()
        } finally {
            instantMotion = false
        }
    }
}

private data class KeyboardFrame(
    val height: Int,
    val targetHeight: Int,
    val windowFocused: Boolean,
    val sheetFocused: Boolean,
    val entry: KeyboardSheetEntry?,
)

/** Reserves the visible sheet as a bottom inset, so IME-aware content does not count it twice. */
private class SheetReservedInsets(private val host: KeyboardSheetHostState) : WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
    override fun getTop(density: Density) = 0
    override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
    override fun getBottom(density: Density) = host.visibleSheetHeight(density)
}

private class KeyboardSheetMotionScheme(
    private val base: MotionScheme,
    private val instant: () -> Boolean,
) : MotionScheme {
    // BottomSheetScaffold shows with the default spatial spec and hides with the fast effects spec.
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        if (instant()) snap() else base.defaultSpatialSpec()

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = base.fastSpatialSpec()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = base.slowSpatialSpec()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = base.defaultEffectsSpec()

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        if (instant()) snap() else base.fastEffectsSpec()

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = base.slowEffectsSpec()
}

private class KeyboardHeightStore(context: Context, orientation: Int) {
    private val preferences =
        context.applicationContext.getSharedPreferences("msngr_keyboard", Context.MODE_PRIVATE)
    private val key = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
        "height_dp_landscape"
    } else {
        "height_dp_portrait"
    }
    private var savedDp = preferences.getFloat(key, 0f)

    fun heightPx(density: Density): Int = with(density) {
        (if (savedDp > 0f) savedDp.dp else DefaultKeyboardSheetHeight).roundToPx()
    }

    fun save(heightPx: Int, density: Density) {
        val heightDp = with(density) { heightPx.toDp().value }
        if (abs(heightDp - savedDp) < 0.5f) return
        savedDp = heightDp
        preferences.edit { putFloat(key, heightDp) }
    }
}

private fun View.hideSoftwareKeyboard() {
    val window = context.findActivity()?.window ?: return
    WindowCompat.getInsetsController(window, this).hide(WindowInsetsCompat.Type.ime())
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
