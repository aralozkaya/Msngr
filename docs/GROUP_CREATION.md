# Group creation and invitations

The **Create a group** action in the main screen's expandable FAB opens a full-screen
`BasicAlertDialog`. A container transform expands the action's bounds and rounded shape into the
dialog, swaps its content for the form, and reverses on Close, Back, or successful creation before
opening the group. The form uses a fixed top bar with Close and Create group actions, scrollable
fields, and system-bar and keyboard insets. Submit actions use text buttons. It accepts a group name, optional contact selection,
and additional email addresses, plus an optional message included with each invitation. An empty selection creates
an owner-only group. Group details offers **Invite members** to owners and administrators and
**Leave group** to administrators and ordinary members. Owners cannot leave; the client checks
the current server role before sending a leave request. Leaving preserves local conversation history.

**Invite members** expands the group-details invite icon into the same full-screen `BasicAlertDialog`,
with the group name in the form and a **Send** text button in the top bar. Close, Back, and successful
submission reverse the transform into the invite icon. Invitation failures keep the recipients and
message for retry, and dismissal is disabled while sending.

**Requests & invitations** in the main menu lists pending group invitations with Join and Decline
actions alongside contact requests. Both sections use the contact-request card design and
Material 3 Expressive components. Pull-to-refresh reloads both lists, even on an empty page.
Invitations discovered at login and through live roster notifications share this inbox. They remain
separate from accepted groups. Android notifications use the dedicated
`group_invitations` channel, named **Group invitations**. Sound, vibration, importance, and blocking
are configured in Android's system notification settings. There is no app preference for this channel.
Both contact-request and group-invitation notification links open the combined page and are scoped
to the authenticated account and service; logout clears its notification.

## Protocol and recovery

CreateCircle creates the group and returns its GUID. Each invitation then uses CreateContact
against that group followed by ManageWLConnection with role 3/action 1. The optional message is
an MSN.IM.InviteMessage annotation on the invitation, not a field on CreateCircle.

Accepting uses ManageWLConnection with role 0/action 1; declining uses role 0/action 2. A non-owner
leaves through BreakConnection with the CircleLeave scenario. Every mutation uses the group ID,
never its display name. The current server role is checked before a leave operation.

Pending CircleInverseInfo records use WaitingResponse. InviterEmail, InviterName, and InviterMessage
belong to the matching circle relationship. Pending invitations stay separate from accepted groups.
See [request messages](REQUEST_MESSAGES.md) for message scoping and service limitations.

The service can publish a notification before its membership database flushes. Roster refreshes
coalesce events and perform one bounded follow-up after an address-book notification. Successful
local operations suppress stale snapshots. Creation retains its returned GUID on an invitation
failure, and retries recover persisted invitations without creating another group or sending twice.

## Testing

GroupManagementTest and RequestMessagesTest cover synthetic SOAP fixtures, validation, role checks,
and recovery. GroupManagementInstrumentedTest, LeaveGroupDialogInstrumentedTest, and invitation
notification tests use mock data. Tests do not contain live credentials or personal account IDs.
