package org.aralozkaya.msngr.enums

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.aralozkaya.msngr.R

/** MSNP presence states exposed by the app. */
enum class Presence(
    val label: String,
    val msnpCode: String,
    val priority: Int,
    val isOnline: Boolean,
    @param:StringRes val labelRes: Int,
) {
    Online("Online", "NLN", 0, true, R.string.presence_online),
    Busy("Busy", "BSY", 1, true, R.string.presence_busy),
    Away("Away", "AWY", 2, true, R.string.presence_away),
    Offline("Offline", "HDN", 3, false, R.string.presence_offline);

    val ownStatusLabelRes: Int
        @StringRes get() = if (this == Offline) R.string.presence_appear_offline else labelRes

    fun ownStatusLabel(): String = if (this == Offline) "Appear offline" else label

    fun label(context: Context): String = context.getString(labelRes)

    fun ownStatusLabel(context: Context): String = context.getString(ownStatusLabelRes)

    @Composable
    fun displayLabel(): String = stringResource(labelRes)

    @Composable
    fun displayOwnStatusLabel(): String = stringResource(ownStatusLabelRes)

    companion object {
        fun fromStorageValue(value: String): Presence = when (value) {
            "Available" -> Online // migration from early builds
            else -> entries.firstOrNull { it.name == value } ?: Online
        }

        fun fromMsnpCode(value: String): Presence = when (value.uppercase()) {
            "NLN" -> Online
            "BSY" -> Busy
            "IDL", "BRB", "AWY", "PHN", "LUN" -> Away
            "HDN", "FLN" -> Offline
            else -> Offline
        }
    }
}
