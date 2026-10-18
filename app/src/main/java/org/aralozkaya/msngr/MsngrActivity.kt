package org.aralozkaya.msngr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

abstract class MsngrActivity : ComponentActivity() {
    protected open val observesConnectionLoss: Boolean = true

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
