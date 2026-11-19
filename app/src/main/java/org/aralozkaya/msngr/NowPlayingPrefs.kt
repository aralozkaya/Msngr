package org.aralozkaya.msngr

import android.content.Context
import androidx.core.content.edit

object NowPlayingPrefs {
    private const val PROFILE_PREFS = "msngr_profile"
    private const val KEY_SHARE_NOW_PLAYING = "share_what_im_listening_to"

    fun isEnabled(
        context: Context,
        account: AccountIdentity? = SessionPrefs.preferenceAccount(context),
    ): Boolean {
        val target = account ?: return false
        return AccountPreferences.forAccount(context, PROFILE_PREFS, target)
            .getBoolean(KEY_SHARE_NOW_PLAYING, false)
    }

    fun setEnabled(
        context: Context,
        enabled: Boolean,
        account: AccountIdentity? = SessionPrefs.preferenceAccount(context),
    ) {
        val target = account ?: return
        AccountPreferences.forAccount(context, PROFILE_PREFS, target).edit {
            putBoolean(KEY_SHARE_NOW_PLAYING, enabled)
        }
    }
}
