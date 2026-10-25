@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package org.aralozkaya.msngr.pages.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A revealable primary action that commits only on release beyond the haptic threshold. */
@Composable
internal fun ContactReadAction(
    contactId: String,
    unread: Boolean,
    onReadStateChange: ((Boolean) -> Unit)?,
    onClick: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit,
) {
    if (onReadStateChange == null) {
        content(Modifier, onClick)
        return
    }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val actionWidthPx = with(density) { 68.dp.toPx() }
    var rowWidth by remember(contactId) { mutableFloatStateOf(0f) }
    // Positive distance reveals the end-aligned action in either layout direction.
    var distance by remember(contactId) { mutableFloatStateOf(0f) }
    var dragging by remember(contactId) { mutableStateOf(false) }
    var armed by remember(contactId) { mutableStateOf(false) }
    var actionInProgress by remember(contactId) { mutableStateOf(false) }
    var animationJob by remember(contactId) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val motion = remember { tween<Float>(durationMillis = 200, easing = FastOutSlowInEasing) }
    val latestChangeReadState by rememberUpdatedState(onReadStateChange)
    val latestUnread by rememberUpdatedState(unread)
    val threshold = (rowWidth + actionWidthPx) / 2f
    val label = if (unread) "Mark as read" else "Mark as unread"
    val actionColors = IconButtonDefaults.filledIconButtonColors()
    val tooltipState = rememberTooltipState()
    val settle: (Boolean) -> Unit = { commit ->
        tooltipState.dismiss()
        animationJob?.cancel()
        dragging = false
        armed = false
        actionInProgress = commit
        val destination = when {
            commit -> rowWidth
            distance >= actionWidthPx / 2f -> actionWidthPx.coerceAtMost(rowWidth)
            else -> 0f
        }
        animationJob = scope.launch {
            try {
                animate(distance, destination, animationSpec = motion) { value, _ -> distance = value }
                if (commit) {
                    latestChangeReadState(!latestUnread)
                    // Read/unread retains the contact, so bring it back after it clears the row.
                    animate(distance, 0f, animationSpec = motion) { value, _ -> distance = value }
                }
            } finally {
                if (commit) actionInProgress = false
            }
        }
    }
    val close: () -> Unit = {
        tooltipState.dismiss()
        if (!actionInProgress) {
            animationJob?.cancel()
            animationJob = scope.launch {
                animate(distance, 0f, animationSpec = motion) { value, _ -> distance = value }
            }
        }
    }
    val performAction: () -> Unit = {
        if (!actionInProgress) {
            latestChangeReadState(!latestUnread)
            close()
        }
    }
    // Stable pointer input observes current callbacks without restarting during a drag.
    val startDrag by rememberUpdatedState<() -> Unit> {
        tooltipState.dismiss()
        if (!actionInProgress) {
            animationJob?.cancel()
            dragging = true
            armed = false
        }
    }
    val drag by rememberUpdatedState<(Float) -> Unit> { delta ->
        if (dragging && !actionInProgress) {
            distance = (distance - delta * direction).coerceIn(0f, rowWidth)
            val nowArmed = rowWidth > actionWidthPx && distance >= threshold
            if (nowArmed && !armed) {
                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            }
            armed = nowArmed
        }
    }
    val release by rememberUpdatedState<() -> Unit> {
        if (dragging) settle(armed)
    }
    val cancel by rememberUpdatedState<() -> Unit> {
        if (dragging) settle(false)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { rowWidth = it.width.toFloat() }
            .pointerInput(contactId) {
                detectHorizontalDragGestures(
                    onDragStart = { startDrag() },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        drag(delta)
                    },
                    onDragEnd = { release() },
                    onDragCancel = { cancel() },
                )
            },
    ) {
        // Hidden actions must not be focusable through the card covering them.
        if (distance > 0.5f) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(label) } },
                    state = tooltipState,
                    enableUserInput = !dragging && !actionInProgress,
                    modifier = Modifier
                        .width(with(density) { distance.coerceAtLeast(actionWidthPx).toDp() })
                        .fillMaxHeight()
                        .padding(start = 4.dp),
                ) {
                    FilledIconButton(
                        onClick = performAction,
                        enabled = !actionInProgress,
                        // Prevent repeat actions without fading the button during the return slide.
                        colors = actionColors.copy(
                            disabledContainerColor = actionColors.containerColor,
                            disabledContentColor = actionColors.contentColor,
                        ),
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            if (unread) Icons.Default.MarkEmailRead else Icons.Default.MarkEmailUnread,
                            contentDescription = label,
                        )
                    }
                }
            }
        }
        content(
            Modifier
                .offset { IntOffset(-distance.roundToInt(), 0) }
                .semantics {
                    stateDescription = if (unread) "Unread" else "Read"
                    customActions = listOf(CustomAccessibilityAction(label) {
                        performAction()
                        true
                    })
                },
            {
                if (distance > 0.5f) close() else if (!actionInProgress) onClick()
            },
        )
    }
}
