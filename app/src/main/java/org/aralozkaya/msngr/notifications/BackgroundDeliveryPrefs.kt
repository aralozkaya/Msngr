package org.aralozkaya.msngr.notifications

import android.content.Context
import androidx.core.content.edit
import org.aralozkaya.msngr.AccountPreferences

/** Keeps the direct MSNP session foregrounded while the UI is in the background. */
object BackgroundDeliveryPrefs {
    private const val PREFS = "background_delivery"
    private const val KEY_PERSISTENT_CONNECTION = "persistent_connection"

    fun keepsConnectionAlive(context: Context): Boolean =
        AccountPreferences.preferences(context, PREFS)
            .getBoolean(KEY_PERSISTENT_CONNECTION, true)

    fun setKeepsConnectionAlive(context: Context, enabled: Boolean) {
        AccountPreferences.preferences(context, PREFS).edit {
            putBoolean(KEY_PERSISTENT_CONNECTION, enabled)
        }
    }
}
