package org.aralozkaya.msngr

import android.content.Context
import androidx.core.content.edit

object DisplayPictureSyncPrefs {
    private const val KEY_BEHAVIOR = "display_picture_sync_behavior"

    fun behavior(context: Context, account: AccountIdentity? = SessionPrefs.preferenceAccount(context)): DisplayPictureSyncBehavior {
        val stored = AccountPreferences.forAccount(context, "msngr_profile", account).getString(KEY_BEHAVIOR, null)
        return DisplayPictureSyncBehavior.entries.firstOrNull { it.name == stored } ?: DisplayPictureSyncBehavior.AskEveryTime
    }

    fun setBehavior(context: Context, behavior: DisplayPictureSyncBehavior) {
        val account = SessionPrefs.preferenceAccount(context) ?: return
        AccountPreferences.forAccount(context, "msngr_profile", account).edit { putString(KEY_BEHAVIOR, behavior.name) }
    }
}
