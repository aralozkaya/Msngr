package org.aralozkaya.msngr

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.Locale

internal fun String.normalizedAccountEmail(): String = trim().lowercase(Locale.ROOT)

/** Only used to recognize and retire the old email-only storage format. */
internal fun accountStorageKey(email: String): String = MessageDigest.getInstance("SHA-256")
    .digest(email.normalizedAccountEmail().toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

/** User preferences share the same account boundary as remembered credentials. */
internal object AccountPreferences {
    private val names = listOf(
        "msngr_profile",
        "msngr_appearance",
        "msngr_emoticons",
        "msngr_messenger_plus",
        "msngr_contact_invitations",
        "msngr_in_app_notifications",
        "background_delivery",
    )

    fun preferences(context: Context, name: String): SharedPreferences =
        forAccount(context, name, SessionPrefs.preferenceAccount(context))

    fun forAccount(context: Context, name: String, email: String): SharedPreferences =
        forAccount(context, name, SessionPrefs.accountForEmail(context, email))

    fun forAccount(context: Context, name: String, account: AccountIdentity?): SharedPreferences =
        context.getSharedPreferences(
            "${name}_${account?.storageKey ?: "signed_out"}",
            Context.MODE_PRIVATE,
        )

    fun clear(context: Context, email: String) {
        clear(context, SessionPrefs.accountForEmail(context, email))
    }

    fun clear(context: Context, account: AccountIdentity?) {
        if (account == null || account.email.isBlank()) return
        names.forEach { name -> forAccount(context, name, account).edit { clear() } }
        OwnDisplayPictureStore.clear(context, account)
    }

    /** Shared and email-only preferences cannot prove which service originally saved them. */
    fun discardLegacy(context: Context, email: String = "") {
        names.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit { clear() }
            if (email.isNotBlank()) {
                context.getSharedPreferences("${name}_${accountStorageKey(email)}", Context.MODE_PRIVATE)
                    .edit { clear() }
            }
        }
    }

    fun reload(context: Context) {
        AppearancePrefs.reload(context)
        EmoticonPrefs.reload(context)
        MessengerPlusPrefs.reload(context)
        ContactInvitationPrefs.reload(context)
        org.aralozkaya.msngr.notifications.InAppNotificationPrefs.reload(context)
    }
}
