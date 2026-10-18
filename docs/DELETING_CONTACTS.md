# Deleting contacts

Long-press a contact and choose **Delete contact**, or open its profile and choose the same action.
The confirmation offers **Also block this contact**, unchecked by default. Canceling sends nothing.
Confirming shows a centered Material 3 Expressive `CircularWavyProgressIndicator` over the modal,
disables its controls and dismissal, and waits for completion. Failures leave the dialog open for
retry. The profile closes after success; the main screen shows a success snackbar.

Deletion removes the contact from the Messenger forward list and keeps saved chat history.
Checking the option also removes Allow membership and adds Block membership. An already blocked
contact stays blocked. Forward-list contacts on the Blocked contacts screen also offer deletion
and profile access. Deleted blocked contacts remain on that screen; unblocking
does not re-add them. Group deletion and group creation are outside this feature.

## Packet-free UI testing

Enable **Use mock server** on the debug login screen, sign in as `owner@example.test` with a
dummy password, and add `peer@example.test` using the FAB. Try deleting from the long-press
menu or the profile. The confirmation identifies test data, and a two-second delay makes the
loading overlay visible. No authentication, MSNP, SOAP, or remote picture request is made.
Re-add an unblocked deleted contact through the FAB. If it was blocked, unblock it through Blocked
contacts first. Logging out and signing in to mock mode resets the bundled roster.

## Protocol and consistency

Deletion removes Messenger membership while retaining the address-book record as required by
the service. The optional block action removes Allow membership and adds Block membership.
The client retains history, reconciles roster updates, and preserves the previous local state
when a request fails. Re-adding restores isMessengerUser=true; unblocking alone does not re-add.

ContactDeletingTest, MockContactDeletingTest, DeleteContactDialogInstrumentedTest, and
MockDeleteContactInstrumentedTest exercise these paths without real accounts or credentials.
