# Optional request messages

Add a contact, Create a group, and Invite members offer **Message (optional)**. The message retains
Unicode, indentation, and line breaks. A failed request preserves the form for retry. Initial group
messages accompany member invitations after group creation; an owner-only group has no recipient.
A persisted invitation is not resent or overwritten on retry.

## Sending and receiving

Contact invitations send MSN.IM.InviteMessage in MessengerMemberInfo/PendingAnnotations on
ABContactAdd. Group invitations send the annotation on ManageWLConnection after CreateContact.
CreateCircle contains group properties and caller information, not the invitation message.

Received group text comes from the matching Circle contact's NetworkInfo relationship, identified
by the circle GUID. Contact text is read only from a Pending PassportMember annotation. A group
invitation from the same sender is never a fallback for missing contact-request text.

The request card and decision dialog display supplied text literally, separately from the sender's
display name. Blank messages add no extra text. Concurrent group invitations retain separate text.

## Service limitation

The Escargot service accepts the contact annotation but does not currently return it to the
recipient. The client cannot display text the service omits. Group invitation messages are
returned in InviterMessage and are supported. Other services may return contact annotations.

## Testing

RequestMessagesTest and GroupManagementTest cover XML escaping, blank messages, Unicode, and
relationship scoping. RequestMessagesInstrumentedTest covers the form and request cards.
Fixtures contain synthetic identities and IDs, with authentication values removed or redacted.
