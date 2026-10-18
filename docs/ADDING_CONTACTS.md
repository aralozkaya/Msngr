# Adding contacts

The contacts FAB's **Add a contact** action opens a full-screen `BasicAlertDialog` with an email
address and an optional message. It shares group creation's container transform: the launch action
expands into the form and the animation reverses on Close, Back, or success. A fixed toolbar keeps
Close and Add contact available while the fields scroll with keyboard and system-bar insets.
It validates the address,
rejects the signed-in account, existing forward-list contacts, and blocked contacts, and sends a
Messenger invitation. The form disables submission and dismissal during the request, retains the
address on failure, and closes with a success notice after the server confirms the operation.
While adding, a Material 3 Expressive `CircularWavyProgressIndicator` overlays the form, centered
both horizontally and vertically in the dialog. The overlay fills the full-screen surface without
changing its dimensions; the form and buttons remain disabled until completion.
Mobile numbers, categories, and email delivery are outside this implementation. Custom messages
use WLM's `MessengerMemberInfo/PendingAnnotations` field. The current Escargot service accepts the
request but does not deliver contact-request text; see [`REQUEST_MESSAGES.md`](REQUEST_MESSAGES.md)
for the isolated and overlapping captures. Group creation is documented separately.

After contact deletion, Escargot can retain an address-book record with `isMessengerUser=false`.
The add flow now follows `ABContactAdd` with `ABContactUpdate` setting that property to `true` on
the returned GUID, so re-adding survives roster refreshes and reconnects. See
[`DELETING_CONTACTS.md`](DELETING_CONTACTS.md) for the native-client investigation and live check.

## Packet-free mock UI testing

In a debug build, **Use mock server** is visible on the login screen. Enable it before signing in,
enter `owner@example.test` and any nonempty dummy password, then use the contacts FAB to add
`peer@example.test`. The banner and form identify mock mode. A two-second simulated operation
shows the centered overlay, followed by **Mock contact added.** and a local online contact row.
No MSNP connection, SSO, SOAP request, remote picture fetch, or real invitation is performed.

Mock and real accounts have distinct storage identities, so the dummy credentials do not replace
the real account's credentials. Log out and sign in to mock mode again to reset the bundled roster
and repeat the addition. Validation and duplicate errors use the same form as the real flow.

`MockAddContactInstrumentedTest` exercises the actual main-screen FAB, delayed overlay, success
notice, and visible contact using `MockMsnp18Client` and dummy credentials. The modal tests also
check overlay size, spinner centering, disabled controls, and failure/retry without network access.

## Protocol and consistency

The client uses the Messenger address-book SOAP service and MSNP roster updates. A successful
ABContactAdd is followed by ABContactUpdate with isMessengerUser=true so an old address-book
record can be re-added reliably. XML values are escaped, contact identities use normalized
addresses, and roster refreshes retain pending local operations until the server catches up.
An address-book failure is reported to the form without manufacturing a local contact.

ContactAddingTest, MockContactAddingTest, AddContactDialogInstrumentedTest, and
MockAddContactInstrumentedTest cover validation, protocol payloads, and the UI with synthetic data.
