@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReusableContent
import androidx.compose.runtime.ReusableContentHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import org.aralozkaya.msngr.pages.components.MsngrCircularWavyProgressIndicator
import org.aralozkaya.msngr.pages.components.MsngrLinearWavyProgressIndicator
import org.aralozkaya.msngr.pages.components.MsngrLoadingIndicator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProgressIndicatorInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun determinateWavesSurviveDetachAndReattach() {
        compose.mainClock.autoAdvance = false
        val moved = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                val indicators = remember {
                    movableContentOf { StaticWaves() }
                }
                Column(Modifier.padding(32.dp)) {
                    Column { if (!moved.value) indicators() }
                    Column { if (moved.value) indicators() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val linear = compose.onNodeWithTag("linear").captureToImage()
        val circular = compose.onNodeWithTag("circular").captureToImage()
        repeat(3) {
            compose.runOnIdle { moved.value = !moved.value }
            compose.mainClock.advanceTimeBy(500)
            assertSameDrawing("linear", linear)
            assertSameDrawing("circular", circular)
        }
    }

    @Test fun scrollingOffscreenAndBackPreservesWaves() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.size(300.dp, 140.dp).testTag("list")) {
                    items((0..40).toList(), key = { it }, contentType = { "loader" }) { index ->
                        Column(Modifier.height(100.dp)) { StaticWaves(suffix = "-$index") }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val linear = compose.onNodeWithTag("linear-0").captureToImage()
        val circular = compose.onNodeWithTag("circular-0").captureToImage()
        repeat(2) {
            compose.onNodeWithTag("list").performScrollToIndex(30)
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("linear-0").assertDoesNotExist()
            compose.onNodeWithTag("list").performScrollToIndex(0)
            compose.mainClock.advanceTimeBy(500)
            assertSameDrawing("linear-0", linear)
            assertSameDrawing("circular-0", circular)
        }
    }

    @Test fun reusableSlotsPreserveWavesAndAnimatedLoaders() {
        compose.mainClock.autoAdvance = false
        val active = mutableStateOf(true)
        val slot = mutableStateOf(0)
        compose.setContent {
            MaterialTheme {
                ReusableContentHost(active.value) {
                    ReusableContent(slot.value) {
                        Column(Modifier.padding(32.dp)) {
                            StaticWaves()
                            AnimatedLoaders()
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val linear = compose.onNodeWithTag("linear").captureToImage()
        val circular = compose.onNodeWithTag("circular").captureToImage()
        repeat(2) {
            compose.runOnIdle { active.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("linear").assertDoesNotExist()
            compose.runOnIdle { active.value = true; slot.value++ }
            compose.mainClock.advanceTimeBy(500)
            assertSameDrawing("linear", linear)
            assertSameDrawing("circular", circular)
            assertLoadersAnimate()
        }
    }

    @Test fun indeterminateLoadersKeepAnimatingAfterReattachment() {
        compose.mainClock.autoAdvance = false
        val moved = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                val indicators = remember { movableContentOf { AnimatedLoaders() } }
                Column(Modifier.padding(32.dp)) {
                    Column { if (!moved.value) indicators() }
                    Column { if (moved.value) indicators() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        repeat(3) {
            compose.runOnIdle { moved.value = !moved.value }
            compose.mainClock.advanceTimeBy(500)
            assertLoadersAnimate()
        }
    }

    @Test fun backgroundAndForegroundPreserveProgressAndResumeAnimations() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Column(Modifier.padding(32.dp)) {
                    StaticWaves()
                    AnimatedLoaders()
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val linear = compose.onNodeWithTag("linear").captureToImage()
        val circular = compose.onNodeWithTag("circular").captureToImage()
        repeat(2) {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.mainClock.advanceTimeBy(500)
            assertSameDrawing("linear", linear)
            assertSameDrawing("circular", circular)
            compose.onNodeWithTag("linear").assertRangeInfoEquals(ProgressBarRangeInfo(0.5f, 0f..1f))
            assertLoadersAnimate()
        }
    }

    @Test fun savedScreenStateRestoresProgressWithWorkingLoaders() {
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        var changeProgress: (Float) -> Unit = {}
        restoration.setContent {
            var progress by rememberSaveable { mutableFloatStateOf(0.2f) }
            changeProgress = { progress = it }
            MaterialTheme {
                Column(Modifier.padding(32.dp)) {
                    StaticWaves(progress = { progress })
                    AnimatedLoaders()
                }
            }
        }
        compose.runOnIdle { changeProgress(0.6f) }
        compose.mainClock.advanceTimeBy(500)
        val linear = compose.onNodeWithTag("linear").captureToImage()
        val circular = compose.onNodeWithTag("circular").captureToImage()
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("linear").assertRangeInfoEquals(ProgressBarRangeInfo(0.6f, 0f..1f))
        assertSameDrawing("linear", linear)
        assertSameDrawing("circular", circular)
        assertLoadersAnimate()
    }

    @Composable
    private fun StaticWaves(suffix: String = "", progress: () -> Float = { 0.5f }) {
        MsngrLinearWavyProgressIndicator(progress, Modifier.testTag("linear$suffix"), waveSpeed = 0.dp)
        MsngrCircularWavyProgressIndicator(progress, Modifier.testTag("circular$suffix"), waveSpeed = 0.dp)
    }

    @Composable
    private fun AnimatedLoaders() {
        MsngrLinearWavyProgressIndicator(Modifier.testTag("busy-linear"), color = Color.Red)
        MsngrCircularWavyProgressIndicator(Modifier.testTag("busy-circular"))
        MsngrCircularWavyProgressIndicator(progress = { 0.5f }, modifier = Modifier.testTag("busy-progress"))
        MsngrLoadingIndicator(Modifier.testTag("morph"))
    }

    private fun assertLoadersAnimate() {
        val tags = listOf("busy-linear", "busy-circular", "busy-progress", "morph")
        val before = tags.associateWith { compose.onNodeWithTag(it).captureToImage() }
        compose.mainClock.advanceTimeBy(250)
        tags.forEach { tag ->
            val after = compose.onNodeWithTag(tag).captureToImage()
            assertFalse("$tag stopped animating", before.getValue(tag).sameDrawingAs(after))
        }
        compose.onNodeWithTag("busy-linear").assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        val wave = compose.onNodeWithTag("busy-linear").captureToImage().toPixelMap()
        val rows = (0 until wave.height).filter { y ->
            (0 until wave.width).any { x ->
                val color = wave[x, y]
                color.red > 0.8f && color.green < 0.2f && color.blue < 0.2f
            }
        }
        val span = (rows.lastOrNull() ?: 0) - (rows.firstOrNull() ?: 0)
        assertTrue("Linear loader became flat", span > 5 * compose.activity.resources.displayMetrics.density)
    }

    private fun assertSameDrawing(tag: String, expected: ImageBitmap) {
        val actual = compose.onNodeWithTag(tag).captureToImage()
        assertTrue("$tag lost its waveform after restoration", expected.sameDrawingAs(actual))
    }

    private fun ImageBitmap.sameDrawingAs(other: ImageBitmap): Boolean =
        width == other.width && height == other.height &&
            toPixelMap().buffer.contentEquals(other.toPixelMap().buffer)
}
