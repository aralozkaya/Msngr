package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aralozkaya.msngr.pages.components.ContactReadAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactReadActionInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val unread = mutableStateOf(true)
    private var changes = 0
    private var opened = 0
    private val haptics = mutableListOf<HapticFeedbackType>()
    private val haptic = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            haptics += hapticFeedbackType
        }
    }

    private fun showRow(direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides direction,
                    LocalHapticFeedback provides haptic,
                ) {
                    Box(Modifier.testTag("swipe_container")) {
                        ContactReadAction(
                            contactId = "contact",
                            unread = unread.value,
                            onReadStateChange = { unread.value = it; changes++ },
                            onClick = { opened++ },
                        ) { modifier, onClick ->
                            Surface(onClick = onClick, modifier = modifier.fillMaxWidth().testTag("contact")) {
                                Text("Contact", Modifier.padding(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun revealAction(durationMillis: Long = 1_000) {
        val distance = with(compose.density) { 72.dp.toPx() }
        compose.onNodeWithTag("swipe_container").performTouchInput {
            swipe(center, center - Offset(distance, 0f), durationMillis = durationMillis)
        }
    }

    private fun holdPastThreshold() {
        compose.onNodeWithTag("swipe_container").performTouchInput {
            down(Offset(width * 0.95f, centerY))
            moveTo(Offset(width * 0.15f, centerY), delayMillis = 300)
        }
    }

    @Test
    fun swipeRevealsCurrentActionAndRepeatedActionsReturnCardToClosedState() {
        showRow()
        compose.onNodeWithContentDescription("Mark as read").assertDoesNotExist()
        revealAction()
        compose.runOnIdle { assertTrue(unread.value); assertEquals(0, changes); assertTrue(haptics.isEmpty()) }
        compose.onNodeWithContentDescription("Mark as read").performClick()
        compose.runOnIdle { assertFalse(unread.value); assertEquals(1, changes) }
        compose.onNodeWithContentDescription("Mark as unread").assertDoesNotExist()
        revealAction()
        compose.onNodeWithContentDescription("Mark as unread").performClick()
        compose.runOnIdle { assertTrue(unread.value); assertEquals(2, changes); assertEquals(0, opened) }
        compose.onNodeWithTag("contact").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun reverseSwipeClosesWithoutChangingReadStateOrOpeningChat() {
        showRow()
        revealAction()
        compose.onNodeWithTag("swipe_container").performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription("Mark as read").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, changes); assertEquals(0, opened) }
    }

    @Test
    fun rtlFullSwipeTriggersActionWithRightwardSwipe() {
        showRow(LayoutDirection.Rtl)
        compose.onNodeWithTag("swipe_container").performTouchInput { swipeRight() }
        compose.runOnIdle {
            assertFalse(unread.value)
            assertEquals(1, changes)
            assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), haptics)
        }
        compose.onNodeWithContentDescription("Mark as unread").assertDoesNotExist()
    }

    @Test
    fun fullSwipeTriggersOnceWithoutTappingAndAllowsTheOppositeActionNext() {
        showRow()
        compose.onNodeWithTag("swipe_container").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertFalse(unread.value); assertEquals(1, changes); assertEquals(0, opened) }
        compose.onNodeWithContentDescription("Mark as unread").assertDoesNotExist()
        compose.onNodeWithTag("swipe_container").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertTrue(unread.value); assertEquals(2, changes); assertEquals(0, opened) }
        compose.onNodeWithContentDescription("Mark as read").assertDoesNotExist()
        compose.onNodeWithTag("contact").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun revealedActionHasNoVisibleText() {
        showRow()
        revealAction()
        compose.onNodeWithText("Mark as read").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mark as read").performClick()
        compose.runOnIdle { assertFalse(unread.value); assertEquals(1, changes) }
    }

    @Test
    fun crossingThresholdHapticsOnceAndWaitsForReleaseToCommit() {
        showRow()
        holdPastThreshold()
        compose.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), haptics)
            assertEquals(0, changes)
        }
        compose.onNodeWithTag("swipe_container").performTouchInput {
            moveTo(Offset(width * 0.05f, centerY), delayMillis = 200)
        }
        compose.runOnIdle { assertEquals(1, haptics.size); assertEquals(0, changes) }
        compose.onNodeWithTag("swipe_container").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, changes); assertEquals(1, haptics.size) }
    }

    @Test
    fun retreatingBelowThresholdBeforeReleaseDoesNotCommit() {
        showRow()
        holdPastThreshold()
        compose.onNodeWithTag("swipe_container").performTouchInput {
            moveTo(Offset(width * 0.8f, centerY), delayMillis = 300)
            up()
        }
        compose.runOnIdle { assertEquals(1, haptics.size); assertEquals(0, changes); assertTrue(unread.value) }
    }

    @Test
    fun cancellingPastThresholdDoesNotCommit() {
        showRow()
        holdPastThreshold()
        compose.onNodeWithTag("swipe_container").performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(1, haptics.size); assertEquals(0, changes); assertTrue(unread.value) }
    }

    @Test
    fun fastShortSwipeCannotFlingPastTheActionThreshold() {
        showRow()
        revealAction(durationMillis = 60)
        compose.runOnIdle { assertTrue(haptics.isEmpty()); assertEquals(0, changes); assertTrue(unread.value) }
        compose.onNodeWithContentDescription("Mark as read").performClick()
        compose.runOnIdle { assertEquals(1, changes) }
    }
}
