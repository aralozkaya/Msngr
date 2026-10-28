# MSNP18 group chats

Msngr supports existing, accepted MSNP18 circles on Escargot. They appear in the Groups section;
tapping a row opens its conversation and tapping its avatar or conversation header opens Group details.
The dedicated page searches display names and email addresses and shows members' display pictures.
It uses the same toolbar and back navigation as contact profiles. Returning from a member's chat
or profile restores Group details.
Member names use the shared emoticon renderer and retain Messenger Plus formatting.
Tap a member row to open their private chat or their picture to open the contact profile. Members
show their access level (Owner, Administrator, or User) separately from presence. The signed-in
account is marked with a You badge beside its name; its row has no chat action and its picture
opens Edit profile. Missing access levels show Unknown. Members
outside the contact list can still be viewed and messaged; opening their details does not add them
to the contact list. Missing pictures use the standard initials avatar.
Group IDs, rather than display names, identify conversations, so two groups named Test retain
independent messages and histories.

Supported behavior includes UTF-8 text, sender names, typing indicators, nudges, member presence,
saved history, unread counts, group message notifications, and inline replies. The client also
preserves group blocking from the address book and supports the repository's block/unblock calls.
Creating groups, sending and accepting/declining invitations, and leaving as a non-owner are
supported; see [GROUP_CREATION.md](GROUP_CREATION.md). Removing other members is not exposed.
Protocol notifications refresh the contact and group lists when another session changes them,
including leaving, removal, and newly
accepted groups. Ad hoc multi-party switchboard conversations are outside this implementation.

A group is online only when at least one other member is online. The signed-in account never
counts toward availability, including its other endpoints. Offline groups reuse the dismissible
contact-status snackbar and disable the composer, nudges, and sending while retaining the draft.

Handwriting and winks are enabled independently only when all joined, non-offline recipients
advertise support for that feature. Offline members and the signed-in account are ignored;
unknown capabilities disable the feature until a fresh circle `NLN`/`ILN` confirms support.
Busy and Away members still count. Handwriting uses ISF when all recipients accept it and GIF
when that is their common supported format. Presence changes and membership refreshes update
the controls; the send and retry paths check again before submitting a message.

A group removed from the roster disappears from the main page; its open conversation retains its history
and shows an unavailable notice. Groups and Other Contacts have a 24 dp gap and collapse independently
with Material 3 Expressive segmented list items and motion. The child rows slide together from
behind their stationary header as one clipped container, preserving their heights and spacing.
The header's fill and chevron treatment animate between collapsed and expanded states.
Empty-state rows use the same segmented shapes. The shared sort sheet controls both sections and
uses the standard sheet dismissal gestures, without a close button. The section expansion follows
Material's [parent-child list guidance](https://m3.material.io/components/lists/guidelines) and its
[expand/collapse reference video](https://firebasestorage.googleapis.com/v0/b/design-spec/o/projects%2Fgoogle-material-3%2Fimages%2Fmiem9cox-GM3%20-%20Expressive%20-%20Guidelines%2034%20-%20v02.mp4?alt=media&token=2194b99b-9a02-460e-b917-b4818d954232).

## Protocol

The implementation was checked against Windows Live Messenger 2009 traffic and the
[Escargot server source](https://gitlab.com/escargot-chat/server), revision
`9cddb9cfad3e9b09b483527798d7ac37533422a3`. Relevant files are `front/msn/msnp_ns.py`,
`front/msn/http/abservice.py`, and `front/msn/tmpl/abservice/ABFindContactsPagedResponse.xml`.
[Escargot's group documentation](https://escargot.chat/groups/) describes its circle support.

1. SOAP `ABFindContactsPaged` discovers accepted `CircleInverseInfo` records. A separate request
   with the circle's `ABId` loads its accepted members. Pending/deleted records are excluded.
   Member `RelationshipRole` values are retained: 1 is Owner, 2 is Administrator, and 3 is User,
   matching the server's [role definitions](https://gitlab.com/escargot-chat/server/-/blob/master/core/models.py)
   and [circle member response](https://gitlab.com/escargot-chat/server/-/blob/master/front/msn/tmpl/abservice/ABFindContactsPagedResponse.xml).
2. The returned signed `CircleTicket` is preserved exactly, UTF-8/base64 encoded, and sent in
   `USR <transaction> SHA A <ticket>`. Authentication precedes circle subscriptions.
3. A separate `ADL` subscribes circle identities using network type `t="9"`, with list value `3`
   for allowed groups or `5` for blocked groups. Ordinary contacts continue to use type `1`.
4. Circle traffic uses `SDG` on the notification connection. Its three header blocks contain
   routing, reliability, and message metadata. A typical destination is
   `To: 9:<circle-id>@live.com;path=IM`; the sender is `From: 1:<member-email>;epid={<endpoint>}`.
   Text, typing, and nudges use message types `Text`, `Control`/`Typing`, and `Nudge` respectively.
5. `NFY PUT`/`DEL` publications update the circle name and connected roster. Member presence
   addressed with `;via=9:<circle-id>@live.com` updates the group member rather than a direct contact.
6. Address-book `NOT` notifications (`siteid="45705"`), unsolicited `ADL`/`RML`, and publications
   for unknown circles trigger SOAP roster refreshes. Bursts are coalesced; failed refreshes retry
   up to twice, with one bounded follow-up after address-book NOT to catch the server's delayed
   database flush. There is no periodic membership polling. Successful snapshots replace membership
   while preserving current presence, pictures, drafts, and unread state. Failed member/block
   lookups retain the previous details. Newly accepted circles authenticate and subscribe as needed.

All MSNP payload framing uses byte counts. Captured WLM messages sometimes declared an inner
`Content-Length` one byte larger than the body; the enclosing MSNP payload length therefore bounds
the complete incoming body. Outgoing text is limited to 1,200 UTF-8 bytes and a longer message gets
a visible send error. `SDG` has no success acknowledgement: a successful send means socket
submission, while a correlated numeric server rejection updates the message's error state.

History chunks use version 3 to retain sender email and display name. Versions 1 and 2 remain
readable. Read tracking runs only while the conversation's Activity is started, so a retained
background composition cannot clear incoming unread state and cancel its notification.

## Testing

MsnpCirclesTest and LiveCircleClientTest use synthetic fixtures and in-memory transports to
exercise subscriptions, framing, member presence, and group routing. GroupChatUiInstrumentedTest,
GroupDetailsInstrumentedTest, and GroupAvailabilityInstrumentedTest cover the Android interface.
No service account is required for these tests.
