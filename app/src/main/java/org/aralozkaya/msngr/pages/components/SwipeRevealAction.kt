@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    val snapBehavior = AnchoredDraggableDefaults.flingBehavior(state, animationSpec = motion)
    val releaseBehavior = remember(snapBehavior) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                // Distance determines commit; fast short swipes must stop at the revealed button.
                val scrollScope = this
                return with(snapBehavior) { scrollScope.performFling(0f) }
            }
        }
    }
    val scope = rememberCoroutineScope()
    val latestAction by rememberUpdatedState(onAction)
    val distance = -(state.offset.takeUnless(Float::isNaN) ?: 0f).coerceAtMost(0f)
    val committing = state.settledValue == SwipeRevealValue.Committed
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
            .anchoredDraggable(
                state = state,
                orientation = Orientation.Horizontal,
                enabled = !committing,
                interactionSource = interactions,
                flingBehavior = releaseBehavior,
            ),
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
