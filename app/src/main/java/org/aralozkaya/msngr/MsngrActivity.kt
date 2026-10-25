package org.aralozkaya.msngr

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.notifications.InAppNotificationHost
import org.aralozkaya.msngr.ui.theme.MsngrTheme

abstract class MsngrActivity : ComponentActivity() {
    protected open val observesConnectionLoss: Boolean = true

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Add after the activity's content so every authenticated screen shares the same banner.
        findViewById<ViewGroup>(android.R.id.content).addView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { MsngrTheme(fillWindowSurface = false, updateSystemBars = false) { InAppNotificationHost() } }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (observesConnectionLoss) {
            observeConnectionLoss()
        }
    }

    private fun observeConnectionLoss() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val repository = runCatching { appContainer.messengerRepository }.getOrNull()
                    ?: return@repeatOnLifecycle
                combine(repository.signOutReason, repository.connectionIssue) { signedOut, issue ->
                    signedOut to issue
                }.collect { (signedOut, issue) ->
                    if (signedOut != null) {
                        startActivity(LoginActivity.createIntent(this@MsngrActivity, allowAutomaticSignIn = false)
                            .putExtra(LoginActivity.EXTRA_SIGN_OUT_REASON, signedOut)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        finish()
                        return@collect
                    }
                    if (issue == null || !SessionPrefs.canOpenAuthenticatedScreens(this@MsngrActivity)) return@collect
                    startActivity(
                        ReconnectActivity.createIntent(this@MsngrActivity).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        },
                    )
                }
            }
        }
    }
}
