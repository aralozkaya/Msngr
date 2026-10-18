package org.aralozkaya.msngr

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ContactInvitationSettings(
    val useDeclineDefaults: Boolean = false,
    val blockDeclinedInvitations: Boolean = false,
)

object ContactInvitationPrefs {
    private const val PREFS_NAME = "msngr_contact_invitations"
    private const val KEY_USE_DECLINE_DEFAULTS = "use_decline_defaults"
    private const val KEY_BLOCK_DECLINED_INVITATIONS = "block_declined_invitations"

    private val mutableSettings = MutableStateFlow(ContactInvitationSettings())

    fun state(context: Context): StateFlow<ContactInvitationSettings> {
        reload(context)
        return mutableSettings.asStateFlow()
    }

    fun setUseDeclineDefaults(context: Context, enabled: Boolean) {
        reload(context)
        update(context, mutableSettings.value.copy(useDeclineDefaults = enabled))
    }

    fun setBlockDeclinedInvitations(context: Context, block: Boolean) {
        reload(context)
        update(context, mutableSettings.value.copy(blockDeclinedInvitations = block))
    }

    fun saveDeclineDefaults(context: Context, block: Boolean) {
        update(context, ContactInvitationSettings(useDeclineDefaults = true, blockDeclinedInvitations = block))
    }

    internal fun reload(context: Context) {
        val preferences = AccountPreferences.preferences(context, PREFS_NAME)
        mutableSettings.value = ContactInvitationSettings(
            useDeclineDefaults = preferences.getBoolean(KEY_USE_DECLINE_DEFAULTS, false),
            blockDeclinedInvitations = preferences.getBoolean(KEY_BLOCK_DECLINED_INVITATIONS, false),
        )
    }

    private fun update(context: Context, settings: ContactInvitationSettings) {
        AccountPreferences.preferences(context, PREFS_NAME).edit {
            putBoolean(KEY_USE_DECLINE_DEFAULTS, settings.useDeclineDefaults)
            putBoolean(KEY_BLOCK_DECLINED_INVITATIONS, settings.blockDeclinedInvitations)
        }
        mutableSettings.value = settings
    }
}
