# Photos and files

The attachment menu in an online private chat offers **Photos** and **Files**.
Groups and offline transfers are unavailable.

**Files** sends a normal conversation message. An incoming offer has **Accept**,
**Save as**, and **Decline**; an active transfer has **Cancel**. Completed incoming
files have **Save as** and **Open**. Accept downloads to private account storage;
Save as uses Android's document picker. Open grants temporary read access to that
specific completed file. Transfer messages survive relaunches; interrupted transfers
are marked failed. Transfers stream to disk, validate sizes and offsets, and remove
partial files on cancellation, failure, or disconnection. The limit is 2 GiB per file
and eight active transfers per peer. Executable and script extensions are blocked.

Image file messages show a small, orientation-correct thumbnail when the local
file is available; other files show a file icon. Missing or invalid images also
fall back to the icon.

**Photos** invites the other person to a shared slideshow. Either participant can
add photos and select the current image. The viewer uses a swipeable Material carousel and Save photo;
saving requests the unchanged original rather than exporting the JPEG preview.
Original downloads are checked against the advertised SHA-1. Previews respect EXIF
orientation. A share contains up to 32 photos, with previews bounded to 4 MiB each
and outgoing preview data to 32 MiB per session. Download requests are queued;
failed previews can be retried. Stop sharing sends a protocol BYE and a chat notice.
The other viewer retains its received photos and shows who stopped sharing.
Minimizing the sheet, swiping it down, tapping outside, or pressing Back keeps the
share running so the conversation and attachment menu remain usable. A floating
Photos button above the composer reopens it; only Stop sharing, Cancel, or Decline
ends the session locally. Closing an ended share removes its floating button.
Slideshow state is retained during the signed-in session, rather than in history.

## Native evidence and sources

The implementation was compared with network traffic from both installed
Escargot-patched Windows Live Messenger 2009 clients. The reference file actions
were Accept (Alt+C), Save As (Alt+S), Decline (Alt+D), Cancel (Alt+Q), and Open
(Alt+P). Sanitized protocol traces remain ignored; no account credentials are included in
test fixtures or this document.

The supplied 226,898-byte PNG was used with the installed Android app and native
Messenger. Regular files completed in both directions with identical SHA-256
hashes. Photos started from either client; thumbnail and large preview loading,
adding photos from both participants, and saving the unchanged original in both
directions were exercised. Stopping from Messenger retained all four photos in
Android and displayed the remote-stop notice. Captures also cover the native
host's SINDEX assignment for photos added by the invitee.
The final build was checked with a three-photo session: both clients displayed
indexes 1, 2, 3, with matching selection after additions from both sides.

Server routing was inspected in these source revisions:

- [Escargot switchboard](https://gitlab.com/escargot-chat/server/-/blob/9cddb9cfad3e9b09b483527798d7ac37533422a3/front/msn/msnp_sb.py)
- [CrossTalk Azul switchboard](https://git.ugnet.gay/CrossTalk/azul/src/commit/7c0581eb97bc0ff4349bb986c9e69e241079aad3/front/msn/msnp_sb.py)

Both implementations relay the P2P application payload; file and slideshow behavior
belongs to the clients. Inspecting those sources establishes the bridge conventions,
not live verification against CrossTalk. Msngr uses switchboard relay with server
acknowledgements and backpressure, replying Listening:false to direct TCP negotiation.
It does not implement a direct TCP listener. Relay transfers can be slower than
native clients' direct transfers.

## Wire applications

All applications use MSNSLP inside `application/x-msnmsgrp2p` switchboard MSGs
with acknowledgement type D. Both P2Pv1 and endpoint-aware P2Pv2 are supported.
Peer sessions survive switchboard migration so acceptance and data can arrive on
a different connection from the invitation. The installed Android endpoint GUID
is stable, and each sign-in publishes its current MachineGuid before presence;
native preview requests otherwise can target an obsolete endpoint.

| Application | EUF GUID | AppID |
| --- | --- | --- |
| File offer | `5D3E02AB-6190-11D3-BBBB-00C04F795683` | 2 |
| Photos session | `41D3E74E-04A2-4B37-96F8-08ACDB610874` | 35 |
| Type-15 thumbnail/preview object | `A4268EEC-FEC5-49E5-95C3-F126696BDBF6` | 33 |
| Original photo | `758E0488-2F05-411D-8849-CA06BBBAEB5F` | 36 |

File Context is Base64 little-endian binary: context length, version (native 2;
Msngr 3), uint64 file size, preview flags, and a fixed UTF-16LE filename. Incoming
names cannot contain paths or control characters. Files are never written using
an untrusted filename. P2Pv1 file data uses flags `0x01000030` and footer 2 (36
for originals); P2Pv2 uses transfer combination 7 for the first chunk, then 6,
with footer zero. Final binary acknowledgement and SLP BYE complete the transfer.

Photos exchange UTF-16LE MAP commands: ADDH (inviter) and ADDC (invitee) advertise the photo GUID, original
SHA-1, thumbnail and preview MSNObjects; CHG synchronizes selection, PROGRESS
with dp=1 acknowledges a loaded preview, and DEL removes an entry. Type-15
objects are fetched through AppID 33 and validated against their byte count and
SHA-1. JPEG comments give previews a session identity without changing decoded
pixels or the original. Save photo sends AppID 36 with a Base64, NUL-terminated
UTF-16LE MAP REQUESTORIGINAL containing the photo GUID, followed by file framing.
The inviter assigns increasing shared sort indexes, including a SINDEX reply to
ADDC's provisional index -1. CHG carries each participant's own sequence and the
last received peer sequence. P2Pv2 SYN establishes the peer bridge once; subsequent
application invitations reuse it so requesting another photo does not reset an
active slideshow or file transfer. Accepting an offer starts a fresh idle timeout.

## Verification

`FileTransferProtocolTest` covers both P2P framings, acceptance before data,
native version-2 contexts, executable rejection, malformed offsets, partial-file
cleanup, selection and additions from both participants, retained photos after
stop, endpoint object fetching, and original-photo hash validation.
`MsnpSwitchboardTest` covers migration during a 230,000-byte transfer.
`ConversationHistoryStoreTest` covers file metadata and interrupted transfers.
`FileSharingInstrumentedTest` covers file actions and the retained photo viewer.
`FileTransferLiveInteropInstrumentedTest` is opt-in, using privately provisioned
credentials and a PNG; it never accepts credentials as command-line arguments.
The transport probe subscribes only the known reciprocal test peer, avoiding repeated
full address-book fetches. Authentication, presence, switchboards and transfers use
the real service.
