package org.aralozkaya.msngr

import android.content.Context
import androidx.core.content.edit

object DisplayPictureSyncPrefs {
    private const val KEY_BEHAVIOR = "display_picture_sync_behavior"
    private const val KEY_ROAMING_ENABLED = "use_same_picture_and_status_everywhere"

    fun behavior(context: Context, account: AccountIdentity? = SessionPrefs.preferenceAccount(context)): DisplayPictureSyncBehavior {
        val stored = AccountPreferences.forAccount(context, "msngr_profile", account).getString(KEY_BEHAVIOR, null)
        return DisplayPictureSyncBehavior.entries.firstOrNull { it.name == stored } ?: DisplayPictureSyncBehavior.AskEveryTime
    }

    fun setBehavior(context: Context, behavior: DisplayPictureSyncBehavior) {
        val account = SessionPrefs.preferenceAccount(context) ?: return
        AccountPreferences.forAccount(context, "msngr_profile", account).edit { putString(KEY_BEHAVIOR, behavior.name) }
    }

    fun usesSamePictureAndStatusEverywhere(
        context: Context,
        account: AccountIdentity? = SessionPrefs.preferenceAccount(context),
    ): Boolean {
        val target = account ?: return true
        return AccountPreferences.forAccount(context, "msngr_profile", target)
            .getBoolean(KEY_ROAMING_ENABLED, true)
    }

    fun setUsesSamePictureAndStatusEverywhere(
        context: Context,
        enabled: Boolean,
        account: AccountIdentity? = SessionPrefs.preferenceAccount(context),
    ) {
        val target = account ?: return
        AccountPreferences.forAccount(context, "msngr_profile", target).edit {
            putBoolean(KEY_ROAMING_ENABLED, enabled)
        }
    }
}
