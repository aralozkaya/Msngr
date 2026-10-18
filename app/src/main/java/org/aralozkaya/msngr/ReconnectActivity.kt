package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.aralozkaya.msngr.ui.theme.MsngrTheme

class ReconnectActivity : MsngrActivity() {
    override val observesConnectionLoss: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionPrefs.canOpenAuthenticatedScreens(this)) {
            openLogin()
            return
        }

        val repository = runCatching { appContainer.messengerRepository }.getOrNull()
        if (repository == null) {
            openLogin()
            return
        }
        val simulateReconnect = intent.getBooleanExtra(EXTRA_SIMULATE_RECONNECT, false) &&
            SessionPrefs.isMockServerActive()

        setContent {
            MsngrTheme {
                ReconnectScreen(
                    reconnect = {
                        if (simulateReconnect) {
                            delay(MOCK_RECONNECT_DELAY_MILLIS)
                            Result.success(repository.profile.value)
                        } else {
                            repository.reconnect().onSuccess { serverProfile ->
                                restoreSignedInProfile(
                                    repository = repository,
                                    serverProfile = serverProfile,
                                    presence = repository.profile.value.presence,
                                )
                            }
                        }
                    },
                    onConnected = ::openMain,
                    onSessionUnavailable = ::openLogin,
                )
            }
        }
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
        finish()
    }

    private fun openLogin() {
        SessionPrefs.logout()
        startActivity(
            LoginActivity.createIntent(
                context = this,
                allowAutomaticSignIn = true,
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
        finish()
    }

    companion object {
        private const val EXTRA_SIMULATE_RECONNECT = "simulate_reconnect"
        private const val MOCK_RECONNECT_DELAY_MILLIS = 3_000L

        fun createIntent(context: Context): Intent =
            Intent(context, ReconnectActivity::class.java)

        fun createSimulationIntent(context: Context): Intent =
            createIntent(context).putExtra(EXTRA_SIMULATE_RECONNECT, true)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReconnectScreen(
    reconnect: suspend () -> Result<UserProfileSnapshot>,
    onConnected: () -> Unit,
    onSessionUnavailable: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(1) }
    BackHandler(enabled = true) {}

    LaunchedEffect(Unit) {
        while (currentCoroutineContext().isActive) {
            val result = reconnect()
            if (result.isSuccess) {
                onConnected()
                return@LaunchedEffect
            }
            if (
                result.exceptionOrNull()?.message ==
                "The previous sign-in session is no longer available."
            ) {
                onSessionUnavailable()
                return@LaunchedEffect
            }
            delay(retryDelayMillis(attempt))
            attempt += 1
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularWavyProgressIndicator(
                modifier = Modifier
                    .size(64.dp)
                    .testTag("reconnect_progress"),
            )
            Text(
                text = "Restoring connection",
                modifier = Modifier.padding(top = 28.dp),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (attempt == 1) {
                    "The Messenger connection was interrupted. Signing you back in…"
                } else {
                    "The service is not responding yet. Retrying automatically…"
                },
                modifier = Modifier
                    .padding(top = 12.dp)
                    .testTag("reconnect_message"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun retryDelayMillis(attempt: Int): Long = when (attempt) {
    1 -> 1_000L
    2 -> 2_000L
    else -> 5_000L
}
