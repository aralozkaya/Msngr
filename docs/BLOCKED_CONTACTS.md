# Blocked contacts

Blocks are stored by the service as Messenger `Block` memberships. They are not
limited to this installation. The live client loads them with SOAP `FindMembership`
at sign-in, including blocked people who are no longer on the forward contact list.
Blocking and unblocking also send the incremental MSNP `RML`/`ADL` list changes.
Escargot applies these changes to its server contact list.
Groups use their own circle type `9` `ADL` packets: Block-only `l="4"` to block,
and Forward + Allow `l="3"` to unblock. The initial blocked-circle subscription
value `5` must not be reused as a block action; an already joined circle can
acknowledge that subscription without saving the block. See [group chats](GROUP_CHATS.md).
Its [MSNP handlers](https://gitlab.com/escargot-chat/server/-/blob/master/front/msn/msnp_ns.py)
and [membership endpoint](https://gitlab.com/escargot-chat/server/-/blob/master/front/msn/http/abservice.py)
show both paths using the server's contact memberships.

Address-book notifications (`NOT`, `ADL`, and `RML`) trigger a roster refresh. There
is no periodic block-list poll. The **Blocked contacts** screen also refreshes when
opened or resumed, and its app-bar **Refresh** button fetches memberships directly.
Use the same account and service on desktop and mobile to see the same list.

Manual refresh only reads memberships; it does not send contact-list mutations.
A successful response updates blocks and unblocks, adds blocked people outside
the forward list, and removes obsolete block-only rows. Existing contact names,
pictures, forward membership, and conversation history are preserved. Left groups
are not resurrected by stale block memberships. Failed requests keep the current
list and offer retry; responses from an ended session cannot update another account.

Validation covers membership refresh without writes, failed/stale responses,
forward and block-only contacts, group handling, and the toolbar/loading/error UI.
The circle block action was also verified against the real service using an
existing self-only test group: block, membership refresh, fresh sign-in, unblock,
and verification that the original membership and block state were restored.
