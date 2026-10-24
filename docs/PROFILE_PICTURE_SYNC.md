# Display pictures across Messenger sessions

MSNP18 has three related mechanisms:

* `CHG` advertises this endpoint's MSNObject; contacts receive it through `NLN`/`ILN`.
  `0` explicitly means no picture. Missing or malformed metadata is not a removal.
* `UUX`/`UBX` publish personal messages and endpoint data. Endpoint GUIDs distinguish
  places signed in to the same account. P2Pv2 also uses those GUIDs in `P2P-Src`,
  `P2P-Dest`, and MSNSLP addresses. Picture bytes travel separately over P2P and
  are checked against the advertised MSNObject size and hash.
* The authenticated roaming storage service retains a profile and picture between
  sign-ins. A successful presence announcement does not prove a storage write succeeded.

Windows Live Messenger's **Use the same display picture and personal message wherever
I sign in** enables roaming identity (`MSN.IM.RoamLiveProperties`). It saves/loads the
picture and **personal message**, rather than forcing every endpoint's Online, Busy,
Away, or invisible state to match. **Show my display picture and allow others to see
it** is a separate visibility setting. Selecting **(None)** advertises an explicit
removal; hiding a picture or a failed image download is not proof that a roaming file
was deleted.

These distinctions are visible in the original implementations of
[picture publication](https://github.com/RayTF/MSNPSharp/blob/master/MSNPSharp/Owner.cs),
[roaming storage](https://github.com/RayTF/MSNPSharp/blob/master/MSNPSharp/Services/StorageService.cs),
and [roaming preferences](https://github.com/RayTF/MSNPSharp/blob/master/MSNPSharp/Services/ContactService.cs).

## Msngr behavior

Msngr fetches its own picture at sign-in and foreground entry, listens for account
publications and address-book invalidations, and checks every 30 seconds while in the
foreground. Background events retain unresolved choices for the foreground UI.
The existing incoming/local/ask policy applies equally to replacement and removal.
Dismissal suppresses repeated prompts for the same pair until a later foreground entry.

If storage is unavailable, Msngr can fetch the original from another endpoint of its
own account. Only a valid announcement from a known other endpoint can establish a
picture or removal. This uses the same P2P transport as contact pictures; it does not
add a self-contact or turn echoed messages/nudges into incoming chat events. A saved
local picture also remains available to P2P peers during a storage outage.

Transfers honor the destination endpoint and route replies to the requesting peer.
Another place can request a picture through either its own switchboard or one shared
with a contact. The original image bytes and GIF animation are preserved.

Explicit edits, comparison downloads, conflict choices, and durable snapshot writes
are serialized. A delayed download cannot replace a newer saved edit. Choices are
rechecked before applying so an old dialog cannot silently adopt a superseded picture.
Picture updates retain personal messages received from another place when republishing
`UUX`, and the account's local personal-message preference follows those publications.

Before a storage mutation Msngr refreshes the owner's CID/profile/photo identifiers.
It verifies a removal actually took effect; an acknowledged no-op is an error and keeps
the saved original. Incomplete photo metadata and HTTP/download failures remain unknown,
not removals. When the profile service itself is unavailable, an explicit edit can still
be announced and served for the current session; Msngr tells the user that it could not
save a roaming copy for future sign-ins. A rejected upload or unconfirmed storage removal
keeps the previous saved original and reports an error.

## Service behavior and live verification

Escargot's [presence generator](https://gitlab.com/escargot-chat/server/-/blob/master/front/msn/misc.py)
can advertise the first signed-in session's picture. A later endpoint's new upload may
therefore be masked by the older object's metadata. Msngr ignores an unchanged earlier
publication after its own successful upload until a new remote picture is announced.
It cannot make another client adopt a picture that the service has not advertised.

On October 24, 2026, native WLM 2009 and the Android emulator were checked with the
same account. Roaming `GetProfile` returned HTTP 404 despite successful Messenger
authentication. With that real failure present, Android fetched the desktop's
25,050-byte picture over P2P, fetched its 28,689-byte replacement, and detected the
desktop's **(None)** selection. The storage failure was not interpreted as a removal.
The opt-in device test then opened the actual MainActivity comparison dialog for that
native removal and successfully adopted it without a storage upload.
No private picture, credential, raw trace, or account identity is bundled with tests.

Unit tests cover owner CID handles, incomplete/empty storage profiles, storage outages
and no-op removals, replaced resource IDs, same-account transfers, endpoint isolation,
and personal-message retention. Device tests exercise foreground polling, background
invalidations, stale downloads, refreshed choices, explicit local edits, launch/resume,
and the actual comparison UI.

`MultiPresencePictureLiveInstrumentedTest` is opt-in with
`-e multiPresencePictureInterop true`. It requires an encrypted saved real account and
a same-account desktop advertising **(None)**. It checks the real MainActivity prompt
and adopts the removal without uploading a picture, then restores the local baseline
and policy. It never captures private screenshots or prints credentials.
