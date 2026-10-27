# Winks

Long-press the emoticon button in an online conversation, then choose **Winks**.
In groups, every joined, non-offline recipient must advertise wink support. Offline members
and the signed-in account are excluded. Unknown capabilities disable sending until confirmed;
membership, presence, and client updates immediately refresh the menu and any open picker.
The picker uses the same keyboard sheet as emoticons and Messenger Plus! colors. It includes
15 original Messenger winks. Choose a tile to send it and play it locally. Hold a tile and
choose **Preview** to play it without sending; closing the preview returns to the picker.
Preview is available even when sending is unavailable. Custom saved winks also offer **Delete**
in this menu; bundled defaults cannot be deleted. Opening the emoticon button's menu keeps the
keyboard open until a picker is selected.

**Get more winks** opens Escargot's wink catalog in an in-app browser. The site's **InstallContent**
button originally called a Windows ActiveX object, rather than a custom URL handler. Msngr translates
that call into a bounded HTTPS download and asks which package to add. The original nested CAB and
its signature are retained. The catalog itself remains remote, so its contents can change.
The selector offers downloads from the catalog; it has no local-file picker.

An external browser can also launch an Escargot package import with
`msngr://winks?url=<percent-encoded HTTPS package URL>`. This is a Msngr handler, not an existing
Escargot protocol. Local-file and content-provider imports are not supported. Additional winks received from
contacts can still be saved to the collection.

Tap a wink in a conversation to open its standard Material bottom sheet with the thumbnail,
name, and **Play** action. Custom winks offer **Add to saved winks** or **Remove from saved winks**.
The sheet updates when the library changes, including saves from another chat or the catalog;
matching uses the package content hash rather than the sender or name. Default winks cannot be removed.

## Playback and storage

- Ruffle 0.7.1 is bundled locally. No CDN, Flash plugin, or native Flash runtime is required.
- A transparent WebView is created for the active wink and destroyed after playback, dismissal,
  or the activity pausing. The total lifetime is bounded to 18 seconds; playback is capped at 12 seconds.
  There is no prewarming, player pool, wake lock, or background playback. Android may retain its
  shared WebView renderer process after a player is destroyed.
- Incoming winks wait until their conversation is resumed. Pending playback is conflated to the
  latest wink for each conversation; reopening saved history does not replay old winks.
- The **Play winks automatically** and **Wink sounds** switches are under **Emoticons & Winks**
  in app settings. The selector contains no playback switches.
  Audio uses media volume, including in silent and vibrate mode, and the volume buttons adjust
  media volume during playback. The Android animation scale being zero uses a thumbnail.
  Explicit **Play** can play with auto-play disabled; reduced motion still uses a thumbnail.
- Downloads reuse a matching bundled/library/cached CAB by SHA1D. History stores only references,
  not another CAB for each message. The received cache is limited to 32 MiB / 256 items per account;
  installed winks are kept separately, up to 64 MiB / 512 items. The message action sheet offers
  storage actions according to the current library state. Removing a saved wink keeps the open
  message's received copy available for replay.
  Clearing a conversation removes its
  received-cache references; installed winks and bundled defaults remain available.
- Failed or timed-out incoming downloads show the same error icon used by failed outgoing messages.
  Tapping the icon or the wink opens the action sheet with the failure details and **Retry**.
  The sheet's playback and saved-wink options stay visible but disabled while a download is pending or failed,
  including for matching saved or default winks. Retrying starts a fresh transfer with a new timeout;
  it requires the sender to still have the wink available.
- Every completed transfer is checked against the advertised byte count and SHA-1 before parsing.
  Online private and group conversations are supported; offline wink delivery is unavailable.

## Protocol and package handling

Winks use `text/x-msnmsgr-datacast`, `ID: 2`, MSNObject `Type="8"`, and the `0x8000` capability.
The announcement uses MSNP MIME chunking for long original signatures. Unknown MSNObject attributes,
including `stamp`, survive storage and forwarding. A sender's Creator/Friendly/SHA1C envelope is
rebuilt for that sender while the original CAB/signature are preserved.

Group announcements use circle `SDG` messages with `Message-Type: Wink`, the same datacast
body and MSNObject, and circle segmentation for long signatures. The package is shared only
with the recipients online when it was sent. Downloads reuse the sender's point-to-point
MSNObject transport; completion, retries, sender attribution, caching, and playback belong to
the originating group conversation. Cached packages avoid another transfer. Group protocol
tests exercise real P2P packets with in-memory transports; live desktop group wink interoperability
has not been verified.

Downloads use the MSNObject EUF GUID `A4268EEC-FEC5-49E5-95C3-F126696BDBF6`, SLP AppID 1 and the
P2Pv1 footer 1. P2Pv2's bridge footer stays zero. The existing bounded P2P transport is reused;
only a pending wink request can allocate more than the display-picture limit.
The packet conventions are corroborated by [aMSN's MSNObject requests](https://github.com/tomhennigan/amsn/blob/master/amsn/msnp2p.tcl)
and its [wink implementation](https://github.com/tomhennigan/amsn/blob/master/amsn/plugins/winks/winks.tcl).

CAB/MCO parsing is memory-only, with stored, MSZIP, and LZX compression support. Limits are 2 MiB
compressed, 8 MiB expanded, 32 files, and 512 data blocks per folder. Split cabinets, Quantum
compression, invalid checksums, external XML entities, unsafe paths, and oversized SWFs are rejected.
These limits also apply to third-party winks; arbitrary Flash programs are not supported.
Ruffle's own Flash compatibility limits still apply, so universal 100% SWF compatibility is not claimed.

The playback origin intercepts every request and serves only the fixed HTML, bundled Ruffle JS/WASM,
and the selected SWF. All other requests and navigation are blocked. File/content access and
JavaScript interfaces are disabled. Ruffle networking, script access, URL opening and context menus
are disabled. The catalog uses a separate WebView; only trusted Escargot archive URLs can trigger a
network install, and installations require the native Add action.

## Verification

Unit coverage includes the 15 original CABs, the real Escargot Screen Punch MCO wrapper, an LZX repack, malformed
archives, chunked signature preservation, both P2P transports, account isolation, cache cleanup,
and history version 7. Earlier history versions remain readable.

`WinkInstrumentedTest` checks the shared picker, actual Ruffle rendering, muted playback,
request blocking, disposal, and activity pause cleanup. `WinkCatalogInstrumentedTest` is opt-in with
`-e winkCatalogInterop true`; it exercises the live catalog's translated ActiveX installer without
messaging another account. Existing handwriting and voice clip instrumentation should run as
regression coverage. Real desktop-to-Android interoperability and physical-device battery use must
be measured separately; emulator playback does not establish a battery percentage.

Ruffle's pinned files, checksums, license and update procedure are in `assets/ruffle/README.md`.
The adapted Apache-licensed LZX decoder has its source attribution beside the decoder and its license
in `assets/licenses/`. Original bundled CABs come from the locally installed Messenger content pack;
their byte hashes and original stamps are recorded in `assets/winks/catalog.json`.

The existing `HandwritingComposerInstrumentedTest.vibrantHorizontalFloatingToolbarSupportsAllSubmenusDrawingUndoRedoAndSending`
visibility assertion also fails on unchanged commit `fc9c73c` on the local emulator. Its failure was reproduced
in a clean baseline checkout; it is separate from wink playback/import checks. The voice recording position
test now waits for a visible, settled IME before measuring the button, instead of a fixed 500 ms delay.
