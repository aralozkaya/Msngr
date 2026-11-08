@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages.components

import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
internal fun MsngrCircularWavyProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = WavyProgressIndicatorDefaults.indicatorColor,
    trackColor: Color = WavyProgressIndicatorDefaults.trackColor,
    waveSpeed: Dp = WavyProgressIndicatorDefaults.CircularWavelength,
) {
    RestartingIndicator(modifier) {
        CircularWavyProgressIndicator(it, color, trackColor, waveSpeed = waveSpeed)
    }
}

@Composable
internal fun MsngrCircularWavyProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = WavyProgressIndicatorDefaults.indicatorColor,
    trackColor: Color = WavyProgressIndicatorDefaults.trackColor,
    waveSpeed: Dp = WavyProgressIndicatorDefaults.CircularWavelength,
) {
    RestartingIndicator(modifier) {
        CircularWavyProgressIndicator(progress, it, color, trackColor, waveSpeed = waveSpeed)
    }
}

@Composable
internal fun MsngrLinearWavyProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = WavyProgressIndicatorDefaults.indicatorColor,
    trackColor: Color = WavyProgressIndicatorDefaults.trackColor,
    waveSpeed: Dp = WavyProgressIndicatorDefaults.LinearIndeterminateWavelength,
) {
    RestartingIndicator(modifier) {
        LinearWavyProgressIndicator(it, color, trackColor, waveSpeed = waveSpeed)
    }
}

@Composable
internal fun MsngrLinearWavyProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = WavyProgressIndicatorDefaults.indicatorColor,
    trackColor: Color = WavyProgressIndicatorDefaults.trackColor,
    waveSpeed: Dp = WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
) {
    RestartingIndicator(modifier) {
        LinearWavyProgressIndicator(progress, it, color, trackColor, waveSpeed = waveSpeed)
    }
}

@Composable
internal fun MsngrLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = LoadingIndicatorDefaults.indicatorColor,
) {
    RestartingIndicator(modifier) { LoadingIndicator(it, color) }
}

/**
 * Material 3 1.5.0-beta02 clears wave animatables on detach but retains their draw caches.
 * Recreate the indicator's entire animation/drawing state together after a move or lazy reuse,
 * and when returning to a started screen. Loading progress stays owned by the caller.
 * Keep the caller's modifier on the Material node, without an extra layout container, so parent
 * data such as BoxScope.align and RowScope.weight, sizing, and semantics keep their meaning.
 */
@Composable
private fun RestartingIndicator(modifier: Modifier, content: @Composable (Modifier) -> Unit) {
    var generation by remember { mutableIntStateOf(0) }
    val restart: () -> Unit = remember { { generation++ } }
    LifecycleEventEffect(Lifecycle.Event.ON_START, onEvent = restart)
    key(generation) { content(modifier.then(IndicatorRestartElement(restart))) }
}

private data class IndicatorRestartElement(val restart: () -> Unit) : ModifierNodeElement<IndicatorRestartNode>() {
    override fun create() = IndicatorRestartNode(restart)
    override fun update(node: IndicatorRestartNode) { node.restart = restart }
    override fun InspectorInfo.inspectableProperties() { name = "restartIndicatorOnAttach" }
}

private class IndicatorRestartNode(var restart: () -> Unit) : Modifier.Node() {
    private var attachedBefore = false

    override fun onAttach() {
        if (attachedBefore) restart()
        attachedBefore = true
    }

    override fun onReset() { restart() }
}
