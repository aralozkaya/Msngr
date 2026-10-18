package org.aralozkaya.msngr

import android.content.Intent
import androidx.activity.ComponentActivity

internal fun ComponentActivity.redirectToLoginIfNeeded(): Boolean {
    if (SessionPrefs.canOpenAuthenticatedScreens(this)) {
        return false
    }

    val signOutReason = runCatching { appContainer.messengerRepository.signOutReason.value }.getOrNull()
    startActivity(
        LoginActivity.createIntent(this, allowAutomaticSignIn = signOutReason == null).apply {
            signOutReason?.let { putExtra(LoginActivity.EXTRA_SIGN_OUT_REASON, it) }
            addFlags(if (signOutReason != null) Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                else Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
    finish()
    return true
}
