# Group-management protocol fixtures

These fixtures model Windows Live Messenger 2009 group-management requests and responses.
Account names, addresses, group IDs, contact IDs, and numeric account identifiers are synthetic.
Authentication tickets and cache keys are redacted. The fixtures cannot authenticate to a service.

- CreateCircle 1: owner-only group, completed with no invitees.
- CreateCircle 2: creation followed by an email entered during the wizard.
- CreateCircle 3: creation followed by a selection in the native contact picker.
- CreateContact/ManageWLConnection 1: invitation to the owner-only group.
- ManageWLConnection 2: acceptance with role 0/action 1.
- BreakConnection 1: a non-owner leaves the group.
- CreateContact 2/ManageWLConnection 3: an invitation while the recipient is offline.
- ManageWLConnection 4: decline with role 0/action 2.
- pending-1 and pending-2: pending invitation snapshots.

Response filenames preserve the request numbering. See
[group creation](../../../../../docs/GROUP_CREATION.md) for the protocol and recovery behavior.
