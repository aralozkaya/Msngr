@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Compose's anchored swipe-to-reveal pattern, with a full-height Material action button. */
@Composable
internal fun SwipeRevealAction(
    itemKey: String,
    label: String,
    icon: ImageVector,
    onAction: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    closeAfterAction: Boolean = false,
    actionModifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    content: @Composable (Modifier, () -> Unit) -> Unit,
) {
    val state = rememberSaveable(itemKey, saver = AnchoredDraggableState.Saver()) {
        AnchoredDraggableState(SwipeRevealValue.Closed)
    }
    var cancelled by rememberSaveable(itemKey) { mutableStateOf(false) }
    var actionHandled by rememberSaveable(itemKey) { mutableStateOf(false) }
    val interactions = remember(itemKey) { MutableInteractionSource() }
    val dragging by interactions.collectIsDraggedAsState()
    val tooltipState = rememberTooltipState()
    val gap = 4.dp
    val density = LocalDensity.current
    val revealWidth = with(density) { (IconButtonDefaults.mediumContainerSize().width + gap).toPx() }
    val motion = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val scope = rememberCoroutineScope()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val latestAction by rememberUpdatedState(onAction)
    val distance = -(state.offset.takeUnless(Float::isNaN) ?: 0f).coerceAtMost(0f)
    val committing = state.settledValue == SwipeRevealValue.Committed
    val latestCommitting by rememberUpdatedState(committing)
    val commit: () -> Unit = {
        if (!committing && !state.isAnimationRunning) {
            cancelled = false
            actionHandled = false
            tooltipState.dismiss()
            scope.launch { state.animateTo(SwipeRevealValue.Committed, motion) }
        }
    }
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    LaunchedEffect(interactions, state, haptic, view) {
        var gestureActive = false
        var thresholdActive = false
        fun updateThreshold(armed: Boolean) {
            if (!gestureActive || armed == thresholdActive) return
            thresholdActive = armed
            if (armed) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            else {
                // Compose has no deactivate type; AndroidX exposes the standard platform signal.
                ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_DEACTIVATE)
            }
        }
        launch {
            snapshotFlow { state.targetValue == SwipeRevealValue.Committed }
                .collect { armed -> updateThreshold(armed) }
        }
        interactions.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    gestureActive = true
                    thresholdActive = false
                    cancelled = false
                    actionHandled = false
                    tooltipState.dismiss()
                }
                is DragInteraction.Stop -> {
                    updateThreshold(state.targetValue == SwipeRevealValue.Committed)
                    gestureActive = false
                }
                is DragInteraction.Cancel -> {
                    updateThreshold(false)
                    gestureActive = false
                    cancelled = true
                }
            }
        }
    }
    LaunchedEffect(state.settledValue) {
        if (state.settledValue == SwipeRevealValue.Committed) {
            if (!cancelled && !actionHandled) {
                actionHandled = true
                latestAction()
            }
            if (closeAfterAction || cancelled) state.animateTo(SwipeRevealValue.Closed, motion)
        }
    }
    Box(
        modifier = modifier.fillMaxWidth().clipToBounds()
            .onSizeChanged { measured ->
                state.updateAnchors(DraggableAnchors {
                    SwipeRevealValue.Closed at 0f
                    SwipeRevealValue.Revealed at -revealWidth.coerceAtMost(measured.width / 2f)
                    SwipeRevealValue.Committed at -measured.width.toFloat()
                })
            }
            .pointerInput(state, isRtl, interactions, motion) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (latestCommitting) return@awaitEachGesture
                        val startOffset = state.offset.takeUnless(Float::isNaN) ?: 0f
                        val touchSlop = viewConfiguration.touchSlop
                        var pointerId = down.id
                        var totalDx = 0f
                        var totalDy = 0f
                        var initialDelta = 0f
                        var accepted = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull { it.pressed }?.also { pointerId = it.id }
                                ?: break
                            if (change.isConsumed || !change.pressed) break
                            val delta = change.positionChange()
                            totalDx += delta.x
                            totalDy += delta.y
                            if (abs(totalDy) > touchSlop && abs(totalDy) > abs(totalDx)) {
                                break
                            }
                            if (abs(totalDx) > touchSlop) {
                                val directionalDx = if (isRtl) -totalDx else totalDx
                                if (startOffset >= -0.5f && directionalDx > 0f) {
                                    // Let the pager claim the drag first, then cancel the row's pending click.
                                    awaitPointerEvent(PointerEventPass.Final)
                                        .changes.firstOrNull { it.id == pointerId }?.consume()
                                    break
                                }
                                accepted = true
                                initialDelta = directionalDx - sign(directionalDx) * touchSlop
                                change.consume()
                                break
                            }
                        }
                        if (!accepted) return@awaitEachGesture

                        val signals = Channel<SwipeRevealSignal>(Channel.UNLIMITED)
                        signals.trySend(SwipeRevealSignal.Delta(initialDelta))
                        launch {
                            val startInteraction = DragInteraction.Start()
                            interactions.emit(startInteraction)
                            var wasCancelled = false
                            try {
                                state.anchoredDrag(MutatePriority.UserInput) { anchors ->
                                    val minOffset = anchors.positionOf(SwipeRevealValue.Committed)
                                    val maxOffset = anchors.positionOf(SwipeRevealValue.Closed)
                                    for (signal in signals) {
                                        when (signal) {
                                            is SwipeRevealSignal.Delta -> {
                                                val base = state.offset.takeUnless(Float::isNaN) ?: 0f
                                                dragTo((base + signal.px).coerceIn(minOffset, maxOffset))
                                            }
                                            SwipeRevealSignal.End -> break
                                            SwipeRevealSignal.Cancel -> {
                                                wasCancelled = true
                                                break
                                            }
                                        }
                                    }
                                }
                            } finally {
                                interactions.emit(
                                    if (wasCancelled) DragInteraction.Cancel(startInteraction)
                                    else DragInteraction.Stop(startInteraction),
                                )
                            }
                            if (wasCancelled) {
                                state.animateTo(SwipeRevealValue.Closed, motion)
                            } else {
                                // Distance determines settle anchor; ignore fling velocity so fast
                                // short swipes stop cleanly at Revealed without committing.
                                state.animateTo(state.targetValue, motion)
                            }
                        }

                        val completed = horizontalDrag(pointerId) { change ->
                            val deltaX = change.positionChange().x
                            val directionalDelta = if (isRtl) -deltaX else deltaX
                            signals.trySend(SwipeRevealSignal.Delta(directionalDelta))
                            change.consume()
                        }
                        signals.trySend(if (completed) SwipeRevealSignal.End else SwipeRevealSignal.Cancel)
                        signals.close()
                    }
                }
            },
    ) {
        if (distance > .5f) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(label) } },
                    state = tooltipState,
                    enableUserInput = !dragging && !committing && !state.isAnimationRunning,
                    modifier = Modifier.width(with(density) { distance.toDp() }).fillMaxHeight().padding(start = gap),
                ) {
                    FilledIconButton(
                        onClick = commit,
                        enabled = !committing && !state.isAnimationRunning,
                        modifier = actionModifier.fillMaxSize(),
                        shapes = IconButtonDefaults.shapes(
                            shape = IconButtonDefaults.mediumRoundShape,
                            pressedShape = IconButtonDefaults.mediumPressedShape,
                        ),
                        colors = colors.copy(disabledContainerColor = colors.containerColor,
                            disabledContentColor = colors.contentColor),
                    ) {
                        Icon(icon, contentDescription = label,
                            modifier = iconModifier.size(IconButtonDefaults.mediumIconSize))
                    }
                }
            }
        }
        content(
            Modifier.offset { IntOffset(state.requireOffset().roundToInt(), 0) }.semantics {
                customActions = listOf(CustomAccessibilityAction(label) { commit(); true })
            },
            {
                if (!committing) {
                    if (distance > .5f) scope.launch { state.animateTo(SwipeRevealValue.Closed, motion) }
                    else onClick()
                }
            },
        )
    }
}

private enum class SwipeRevealValue { Closed, Revealed, Committed }

private sealed interface SwipeRevealSignal {
    data class Delta(val px: Float) : SwipeRevealSignal
    data object End : SwipeRevealSignal
    data object Cancel : SwipeRevealSignal
}
