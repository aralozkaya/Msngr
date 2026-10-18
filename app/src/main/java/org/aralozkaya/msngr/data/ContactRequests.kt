package org.aralozkaya.msngr.data

import org.w3c.dom.Element

data class ContactRequest(
    val email: String,
    val membershipId: String,
    val displayName: String? = null,
    val message: String? = null,
) {
    val id: String get() = email.trim().lowercase()
}

/** A notification must belong to a successfully authenticated account, including its server. */
data class ContactRequestInbox(
    val accountId: String? = null,
    val requests: List<ContactRequest> = emptyList(),
)

internal data class MessengerMemberships(
    val blockedContacts: List<Contact>,
    val requests: List<ContactRequest>,
)

/** Read only the annotation belonging to this request, never a peer's circle invitation. */
internal fun Element.invitationMessageAnnotation(): String? {
    val annotations = getElementsByTagNameNS("*", "Annotation")
    return (0 until annotations.length).mapNotNull { annotations.item(it) as? Element }
        .firstOrNull { it.groupText("Name") == "MSN.IM.InviteMessage" }
        ?.getElementsByTagNameNS("*", "Value")?.item(0)?.textContent?.takeIf(String::isNotBlank)
}
