@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.aralozkaya.msngr.pages.components

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlin.math.roundToInt

/** A container transform across the activity and BasicAlertDialog's separate windows. */
@Composable
internal fun ContainerTransformDialog(
    sourceBounds: Rect?,
    sourceIcon: ImageVector,
    sourceLabel: String?,
    closing: Boolean,
    busy: Boolean,
    onDismissRequest: () -> Unit,
    onClosed: () -> Unit,
    sourceContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    sourceContentColor: Color = contentColorFor(sourceContainerColor),
    content: @Composable BoxScope.() -> Unit,
) {
    // Snapshot the launch bounds: keyboard/layout changes must not move the return target.
    val launchBounds = remember { sourceBounds?.takeUnless { it.isEmpty } }
    val progress = remember { Animatable(if (launchBounds == null) 1f else 0f) }
    var dialogOrigin by remember { mutableStateOf<Offset?>(null) }
    val currentOnClosed by rememberUpdatedState(onClosed)
    LaunchedEffect(closing, dialogOrigin != null) {
        if (dialogOrigin != null) {
            if (closing && launchBounds == null) {
                currentOnClosed()
                return@LaunchedEffect
            }
            progress.animateTo(if (closing) 0f else 1f,
                tween(durationMillis = if (closing) 250 else 300, easing = FastOutSlowInEasing))
            if (closing) currentOnClosed()
        }
    }

    BasicAlertDialog(
        modifier = Modifier.fillMaxSize(),
        onDismissRequest = { if (!busy && !closing) onDismissRequest() },
        properties = DialogProperties(
            dismissOnBackPress = !busy && !closing,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        val window = (view.parent as DialogWindowProvider).window
        val colors = MaterialTheme.colorScheme
        SideEffect {
            // The Compose transform owns the motion and scrim, including the return animation.
            window.setWindowAnimations(0)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            WindowCompat.getInsetsController(window, view).apply {
                val lightBars = colors.surface.luminance() > 0.5f
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
        Box(Modifier.fillMaxSize()
            .onGloballyPositioned { dialogOrigin = it.positionOnScreen() }
            .drawBehind { drawRect(colors.scrim.copy(alpha = 0.32f * progress.value)) }) {
            val start = launchBounds?.translate(-(dialogOrigin ?: Offset.Zero))
            Box(Modifier.fillMaxSize().drawWithContent {
                val fraction = progress.value
                val end = Rect(Offset.Zero, size)
                val bounds = lerp(start ?: end, end, fraction)
                val radius = (start?.height ?: 0f) / 2f * (1f - fraction)
                val mask = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(radius))) }
                clipPath(mask) {
                    drawRect(lerp(sourceContainerColor, colors.surface, fraction))
                    this@drawWithContent.drawContent()
                }
            }) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    alpha = if (start == null) 1f else ((progress.value - 0.15f) / 0.7f).coerceIn(0f, 1f)
                }, content = content)
                if (start != null) {
                    val density = LocalDensity.current
                    // Swap the menu item's content for the form without scaling or reflowing text.
                    Row(Modifier
                        .absoluteOffset { IntOffset(start.left.roundToInt(), start.top.roundToInt()) }
                        .size(with(density) { start.width.toDp() }, with(density) { start.height.toDp() })
                        .graphicsLayer { alpha = (1f - progress.value / 0.15f).coerceIn(0f, 1f) }
                        .clearAndSetSemantics { }
                        .padding(horizontal = if (sourceLabel == null) 0.dp else 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterHorizontally),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(sourceIcon, contentDescription = null, tint = sourceContentColor)
                        if (sourceLabel != null) Text(sourceLabel,
                            color = sourceContentColor, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
