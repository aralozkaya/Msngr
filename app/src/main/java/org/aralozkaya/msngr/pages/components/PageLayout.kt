package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

internal val LocalScrollVisibilityHost = staticCompositionLocalOf<ScrollVisibilityHost?> { null }

@Stable
internal class ScrollVisibilityHost {
    var activeFieldKey by mutableStateOf<Any?>(null)
        private set
    var requestVersion by mutableIntStateOf(0)
        private set
    private val fieldBounds = mutableStateMapOf<Any, Rect>()

    fun updateBounds(key: Any, boundsInRoot: Rect) {
        fieldBounds[key] = boundsInRoot
    }

    fun setActive(key: Any, active: Boolean) {
        if (active) {
            activeFieldKey = key
            requestVersion++
        } else if (activeFieldKey === key) {
            activeFieldKey = null
        }
    }

    fun remove(key: Any) {
        fieldBounds.remove(key)
        if (activeFieldKey === key) activeFieldKey = null
    }

    fun boundsOf(key: Any): Rect? = fieldBounds[key]
}

@Composable
internal fun Modifier.snapToVisibleWhenActive(active: Boolean): Modifier {
    val host = LocalScrollVisibilityHost.current ?: return this
    val key = remember { Any() }
    LaunchedEffect(host, key, active) {
        host.setActive(key, active)
    }
    DisposableEffect(host, key) {
        onDispose { host.remove(key) }
    }
    return this.onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) {
            host.updateBounds(key, coordinates.boundsInRoot())
        }
    }
}

private data class SnapTrigger(
    val activeKey: Any?,
    val requestVersion: Int,
    val bottomObstaclePx: Int,
)

private fun computeSnapDelta(
    fieldBounds: Rect,
    containerBounds: Rect,
    rootHeightPx: Int,
    topPaddingPx: Float,
    bottomObstaclePx: Int,
    marginPx: Float,
): Float {
    val visibleTop = containerBounds.top + topPaddingPx
    val obstacleTop = (rootHeightPx - bottomObstaclePx).toFloat()
    val visibleBottom = minOf(containerBounds.bottom, obstacleTop)
    if (visibleBottom <= visibleTop) return 0f
    return when {
        fieldBounds.bottom + marginPx > visibleBottom -> {
            val neededDown = (fieldBounds.bottom + marginPx) - visibleBottom
            val maxWithoutHidingTop = (fieldBounds.top - visibleTop).coerceAtLeast(0f)
            neededDown.coerceAtMost(maxWithoutHidingTop)
        }
        fieldBounds.top < visibleTop -> {
            fieldBounds.top - visibleTop
        }
        else -> 0f
    }
}

@Composable
fun PageContentList(
    modifier: Modifier = Modifier,
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val sheetHost = LocalKeyboardSheetHost.current
    val imeInsets = WindowInsets.ime
    val sheetInsets = keyboardSheetWindowInsets()
    val obstacleInsets = remember(imeInsets, sheetInsets) { imeInsets.union(sheetInsets) }
    val bottomObstaclePx = obstacleInsets.getBottom(density)
    val bottomObstacle = with(density) { bottomObstaclePx.toDp() }
    val extraBottom = (bottomObstacle - innerPadding.calculateBottomPadding()).coerceAtLeast(0.dp)
    val currentTopPadding by rememberUpdatedState(innerPadding.calculateTopPadding())
    val visibilityHost = remember { ScrollVisibilityHost() }
    var containerBounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(listState, sheetHost, density, visibilityHost) {
        var previousExpansionPx = 0
        var appliedExpansionScrollPx = 0f
        snapshotFlow {
            sheetHost?.sheetExpansionHeight(density) ?: 0
        }.distinctUntilChanged().collect { expansionPx ->
            val delta = (expansionPx - previousExpansionPx).toFloat()
            previousExpansionPx = expansionPx
            if (visibilityHost.activeFieldKey == null) {
                if (delta > 0f) {
                    var consumed = listState.scrollBy(delta)
                    val remaining = delta - consumed
                    if (remaining >= 1f) {
                        withFrameNanos {}
                        consumed += listState.scrollBy(remaining)
                    }
                    appliedExpansionScrollPx += consumed
                } else if (delta < 0f && appliedExpansionScrollPx > 0f) {
                    val toUndo = (-delta).coerceAtMost(appliedExpansionScrollPx)
                    val undone = -listState.scrollBy(-toUndo)
                    appliedExpansionScrollPx = (appliedExpansionScrollPx - undone).coerceAtLeast(0f)
                }
            }
            if (expansionPx == 0) {
                appliedExpansionScrollPx = 0f
            }
        }
    }

    LaunchedEffect(listState, obstacleInsets, density, visibilityHost, windowInfo) {
        var lastObstaclePx = 0
        var lastActiveKey: Any? = null
        var lastRequestVersion = -1
        snapshotFlow {
            SnapTrigger(
                activeKey = visibilityHost.activeFieldKey,
                requestVersion = visibilityHost.requestVersion,
                bottomObstaclePx = obstacleInsets.getBottom(density),
            )
        }.distinctUntilChanged().collect { trigger ->
            val key = trigger.activeKey
            val obstacleGrew = trigger.bottomObstaclePx > lastObstaclePx
            val fieldChanged = key != lastActiveKey || trigger.requestVersion != lastRequestVersion
            lastObstaclePx = trigger.bottomObstaclePx
            lastActiveKey = key
            lastRequestVersion = trigger.requestVersion
            if (key == null || trigger.bottomObstaclePx <= 0 || (!obstacleGrew && !fieldChanged)) {
                return@collect
            }
            val container = containerBounds ?: return@collect
            var field = visibilityHost.boundsOf(key)
            if (field == null) {
                withFrameNanos {}
                field = visibilityHost.boundsOf(key) ?: return@collect
            }
            val rootHeightPx = maxOf(
                windowInfo.containerSize.height,
                container.bottom.roundToInt(),
            )
            val topPaddingPx = with(density) { currentTopPadding.toPx() }
            val marginPx = with(density) { 12.dp.toPx() }
            val delta = computeSnapDelta(
                fieldBounds = field,
                containerBounds = container,
                rootHeightPx = rootHeightPx,
                topPaddingPx = topPaddingPx,
                bottomObstaclePx = trigger.bottomObstaclePx,
                marginPx = marginPx,
            )
            if (abs(delta) >= 1f) {
                val consumed = listState.scrollBy(delta)
                val remaining = delta - consumed
                if (abs(remaining) >= 1f) {
                    withFrameNanos {}
                    val updatedContainer = containerBounds ?: return@collect
                    val updatedField = visibilityHost.boundsOf(key) ?: return@collect
                    val updatedDelta = computeSnapDelta(
                        fieldBounds = updatedField,
                        containerBounds = updatedContainer,
                        rootHeightPx = maxOf(
                            windowInfo.containerSize.height,
                            updatedContainer.bottom.roundToInt(),
                        ),
                        topPaddingPx = with(density) { currentTopPadding.toPx() },
                        bottomObstaclePx = obstacleInsets.getBottom(density),
                        marginPx = marginPx,
                    )
                    if (abs(updatedDelta) >= 1f) {
                        listState.scrollBy(updatedDelta)
                    }
                }
            }
        }
    }

    CompositionLocalProvider(LocalScrollVisibilityHost provides visibilityHost) {
        LazyColumn(
            state = listState,
            modifier = modifier
                .onGloballyPositioned { coordinates ->
                    if (coordinates.isAttached) {
                        containerBounds = coordinates.boundsInRoot()
                    }
                }
                // Only page content drives the toolbar.
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Horizontal)),
            contentPadding = innerPadding.plus(
                PaddingValues(
                    top = 8.dp + 10.dp * (1.0f - scrollBehavior.state.collapsedFraction),
                    bottom = 18.dp + extraBottom,
                ),
            ),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}
