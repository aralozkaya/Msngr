package org.aralozkaya.msngr

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.aralozkaya.msngr.data.CredentialsRejectedException
import org.aralozkaya.msngr.data.SessionUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ReconnectScreenInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun retriesNetworkErrorsAndReturnsToSignInWhenCredentialsAreRejected() {
        val rejection = CredentialsRejectedException("Password changed")
        var attempts = 0
        var reported: Throwable? = null
        compose.setContent {
            ReconnectScreen(
                reconnect = { Result.failure(if (++attempts == 1) IOException("Network unavailable") else rejection) },
                onConnected = { error("Unexpected connection") },
                onSessionUnavailable = { reported = it },
            )
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle {
            assertEquals(2, attempts)
            assertSame(rejection, reported)
        }
    }

    @Test fun missingSessionReturnsToSignInIndependentlyOfErrorText() {
        val expired = SessionUnavailableException("Logged out")
        var reported: Throwable? = null
        compose.setContent {
            ReconnectScreen(
                reconnect = { Result.failure(expired) },
                onConnected = { error("Unexpected connection") },
                onSessionUnavailable = { reported = it },
            )
        }
        compose.runOnIdle { assertSame(expired, reported) }
    }
}
