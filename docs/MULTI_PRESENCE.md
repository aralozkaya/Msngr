# Device names and active Messenger sessions

Msngr sends the Android device name in MSNP18's `UUX PrivateEndpointData/EpName`.
The application reads `Settings.Global`'s `device_name` on every sign-in, including
reconnections. Missing, blank, or inaccessible settings fall back to `Build.MODEL`,
then `Android` if the model is also unavailable. No Bluetooth permission is needed.
This follows [Android's own device-name lookup](https://android.googlesource.com/platform/packages/apps/Settings/+/master/src/com/android/settings/development/AdbDeviceNamePreferenceController.java).

The name belongs to the device, so switching accounts does not change it. Devices
with identical configured names can still have identical labels; their protocol
endpoint GUIDs distinguish their sessions. Names retain Unicode and are XML escaped.

## Session management

Msngr consumes the account's own `UBX Data` publications as complete session lists.
`EndpointData` provides endpoint GUIDs; `PrivateEndpointData` supplies device names and
presence. Publications for contacts do not enter the account's session list. The current
device remains identified even before its first server publication, and absent remote
endpoints are removed when the next account publication arrives.

When more than one session is active, the menu shows the session count and **Log out…**
opens a Material dialog. Each session has a labeled logout action; the current session
is marked **This device**. The dialog also offers **Log out other sessions** and
**Log out all sessions**. It updates while open, supports scrolling and enlarged text,
disables actions while a request is in progress, and announces errors and completion.
With one session, the existing logout confirmation remains in use.

Settings includes **Sign me out of all other places when I sign in to Msngr**, off
by default and saved separately for each account. Enabling it applies on the next
new sign-in, including background restoration and reconnection. Msngr waits for the
server's account session list, revokes every other endpoint, and keeps this connection.
Sign-in only completes after the other endpoints have confirmed logout; a failed
request closes the new connection and reports an error so it can be retried.

Individual revocation sends `UUN <trid> <email>;{<endpoint-guid>} 4 14` with the
`goawyplzthxbye` payload. Bulk actions target remote endpoints individually before
closing the current connection when requested. A successful request requires both a
server acknowledgement and the endpoint disappearing from the live session list.
If a remote request fails, the dialog reports the error and keeps the current session.
These formats follow the original [MSNPSharp owner implementation](https://github.com/RayTF/MSNPSharp/blob/master/MSNPSharp/Owner.cs).

### Escargot and invisible sessions

When every session appears offline (`HDN`), Escargot rejects remote sign-out with
MSNP error `217` (principal not online). This also affects native Windows Live Messenger
2009. It is a service restriction: Escargot's
[`UUN` handler](https://gitlab.com/escargot-chat/server/-/blob/master/front/msn/msnp_ns.py)
checks the account's aggregate offline status before routing the notification to an endpoint.

Change one session to **Online**, then retry remote sign-out. The target session can
remain invisible. Msngr explains this workaround when the server returns `217`; it
keeps the sessions listed and leaves the chosen presence under the user's control.

Incoming same-account `UBN` type 4 (`goawyplzthxbye`, including the `-nomorempop`
variant) and type 8 (`gtfo`) end this session. The server routes targeted notifications
only to the named endpoint. Other accounts and the client's own broadcast echoes
cannot revoke this session. Server `OUT` commands are terminal except `OUT SSD`,
which indicates maintenance and permits reconnection.

Remote sign-out closes notification and switchboard connections, cancels automatic
reconnection, clears the active local session, stops the background service, and returns
authenticated screens to login with an explanation. It disables automatic sign-in and
saves an account-scoped revocation marker so queued notification work cannot recreate
the session after a process restart. Explicit successful sign-in clears that marker;
remembered credentials and conversation history are retained.

## Testing

MsnpSessionsTest, DeviceEndpointNameTest, and LiveMsnp18ClientContractTest cover endpoint names,
session publications, and logout handling with in-memory transports. SessionsDialogInstrumentedTest
and SessionSettingsInstrumentedTest exercise the Android UI with synthetic device names.

A live Escargot check on October 24, 2026 used native WLM 2009 on Windows and Msngr's
real MSNP18 client on the Android emulator. With both sessions invisible, Msngr received
`217` and the native client's remote sign-out delivered no notification. After Android
changed to Online, the native client's type-4 notification reached Android and emitted
`SignedOut`. In the reverse direction, Android signed in as Online successfully
logged out the invisible WLM endpoint: the server acknowledged `UUN OK`, the desktop
returned to sign-in, and the session count fell from two to one.
The contract tests cover the `217` explanation, retained sessions and
presence, and a successful retry after explicitly changing presence.
