package org.aralozkaya.msngr.data

import java.util.Locale

internal fun isValidContactEmail(raw: String): Boolean {
    val email = raw.trim().lowercase(Locale.ROOT)
    return email.length <= 254 && CONTACT_EMAIL.matches(email) &&
        !email.substringBefore('@').let { it.startsWith('.') || it.endsWith('.') || ".." in it }
}

/** The same validation is used by the form and by clients called without a UI. */
internal fun validateContactAddress(raw: String, ownEmail: String, contacts: List<Contact>): String {
    val email = raw.trim().lowercase(Locale.ROOT)
    require(isValidContactEmail(email)) {
        "Enter a valid instant messaging email address."
    }
    require(!email.equals(ownEmail, ignoreCase = true)) { "You cannot add yourself as a contact." }
    val existing = contacts.firstOrNull { !it.isGroup && it.email.equals(email, ignoreCase = true) }
    require(existing?.isBlocked != true) { "This contact is blocked. Unblock them from Blocked contacts first." }
    require(existing?.isForwardListContact != true) { "This person is already in your contacts." }
    return email
}

private val CONTACT_EMAIL = Regex("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
