# Contact requests

The main three-dot menu opens **Requests & invitations**. Custom contact messages supplied by the
server appear in the request card and its decision dialog. Group cards display the message from
their own circle invitation. The current Escargot server does not deliver contact-request text;
see [`REQUEST_MESSAGES.md`](REQUEST_MESSAGES.md) for native captures and the service limitation.
Its badge and the menu icon badge show
the combined pending contact-request and group-invitation count. The page uses matching cards
under **Contact requests** and **Group invitations** headings. An empty inbox centers
**No pending requests or invitations** in the page's available content area. Pull down to refresh
both lists, including when the inbox is empty; failures preserve the known items and offer Refresh.
The page uses the Material 3 Expressive flexible app bar, animated button shapes, wavy response
progress, and the experimental morphing pull-to-refresh loading indicator.
Each request offers Accept and Decline. The decision dialog follows WLM 2009's Yes/No choices,
an optional **Block invitations from this person** checkbox (off initially), and **Decide later**.
Decide later, back, and dismiss keep the invitation pending. Submission disables the controls and
shows a centered Material 3 Expressive wavy spinner over the existing modal. Errors retain the choice
for retry. Group invitations offer Join and Decline on the same page; joining opens group details.
Categories, mobile numbers, and Microsoft's spam-reporting feature are outside this implementation.

**Settings → Contact Invitations** stores account- and service-specific default decline actions.
**Use default decline actions** is off initially. When enabled, Decline immediately uses the saved
block choice without a modal, with progress and retryable errors on the request card. The page's
decline options stay visible when the toggle is off, with interaction disabled and saved values retained.
Disabling the toggle restores the dialog. Accept continues to open the decision dialog.
The dialog's **Set as default** checkbox saves the selected decline actions and enables the toggle
only after a successful decline. Accepting, deciding later, dismissing, or a failed response does
not save defaults. Removing remembered account data also removes these defaults.

## Server and notification behavior

Membership fetching now parses pending requests alongside blocked memberships during login and
NOT/ADL/RML roster refreshes. Deleted, self, blocked, duplicate, circle, and non-Messenger requests
are excluded. Failed membership lookups retain the known inbox and are retried. The main activity
and requests page refresh memberships on resume, and every decision rechecks the server before
mutating a request that may have been handled elsewhere or withdrawn.

Acceptance saves/restores the Messenger contact, adds Allow membership, checks incremental ADL 2
and ADL 1 acknowledgments, and acknowledges Reverse. Decline acknowledges Reverse without adding
a contact; its optional block persists Block membership and publishes RML 2 / ADL 4. Pending is
cleared last so a failed preliminary save does not deliberately discard the request. These operations
share the roster mutation mutex and check the session after suspended work. Logout clears the inbox;
late responses cannot populate a replacement account.

The independent Android **Contact requests** channel has badging enabled. One ongoing summary
notification uses the pending count, opens the requests page through authenticated login, and stays
until the inbox is resolved or the account logs out. Counts update silently unless there is a new
request. Notifications are scoped to the successfully authenticated account, server, and mock/live
identity, and a notification from a different account cannot open that account's inbox.
Granting notification permission after login also posts the pending summary.

Launcher dots/counts use [Android's notification badging](https://developer.android.com/develop/ui/views/notifications/badges):
the launcher must support it, and notifications/channel badging must be enabled. The two in-app
badges work independently of notification permission.

## Testing

ContactRequestsTest and RequestMessagesTest cover parsing, responses, and invitation-message
isolation with synthetic identities. RequestsPageInstrumentedTest,
ContactInvitationSettingsInstrumentedTest, and notification tests cover UI and Android behavior.
Mock mode supplies a request for owner@example.test from peer@example.test without network access.
