package org.aralozkaya.msngr

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.CredentialsRejectedException
import org.aralozkaya.msngr.notifications.BackgroundConnectionController

/** Restores the session before consuming a document delivered by another app. */
abstract class ExternalDocumentActivity : MsngrActivity() {
    protected var authenticated by mutableStateOf(false)
        private set
    private var waitingForLogin = false
    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waitingForLogin = false
        if (hasSession()) sessionReady() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waitingForLogin = savedInstanceState?.getBoolean(WAITING_FOR_LOGIN) == true
        if (waitingForLogin) return
        if (hasSession()) {
            sessionReady()
            return
        }
        val snapshot = SessionPrefs.readLoginSnapshot(this)
        if (!snapshot.automaticSignIn || snapshot.password.isEmpty() || SessionPrefs.wasRemotelySignedOut(this)) {
            openLogin()
            return
        }
        lifecycleScope.launch {
            restoreRememberedRepository(snapshot).onSuccess {
                sessionReady()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (error is CredentialsRejectedException) {
                    SessionPrefs.invalidateRememberedCredentials(this@ExternalDocumentActivity, snapshot.accountIdentity)
                }
                if (hasSession()) sessionReady() else openLogin()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(WAITING_FOR_LOGIN, waitingForLogin)
    }

    private fun openLogin() {
        waitingForLogin = true
        login.launch(LoginActivity.createReturnAfterLoginIntent(this, allowAutomaticSignIn = false))
    }

    private fun hasSession(): Boolean {
        val active = SessionPrefs.activeAccount() ?: return false
        return appContainer.currentRepository?.signedInAccountIdentity == active
    }

    private fun sessionReady() {
        authenticated = true
        BackgroundConnectionController.startIfEnabled(this)
        onSessionReady()
    }

    protected open fun onSessionReady() = Unit

    private companion object {
        const val WAITING_FOR_LOGIN = "waiting_for_login"
    }
}
