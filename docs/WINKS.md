# Winks

Long-press the emoticon button in an online one-to-one conversation, then choose **Winks**.
The picker uses the same keyboard sheet as emoticons and Messenger Plus! colors. It includes
15 original Messenger winks. Choose a tile to send it and play it locally.

**Get more winks** opens Escargot's wink catalog in an in-app browser. The site's **InstallContent**
button originally called a Windows ActiveX object, rather than a custom URL handler. Msngr translates
that call into a bounded HTTPS download and asks which package to add. The original nested CAB and
its signature are retained. The catalog itself remains remote, so its contents can change.
**Import a file** accepts MCO theme installers and standalone wink CAB/MCT files from other sources.

An external browser can also launch an Escargot package import with
`msngr://winks?url=<percent-encoded HTTPS package URL>`. This is a Msngr handler, not an existing
Escargot protocol. Android content providers can open supported CAB/Messenger MIME types directly.
Packages from other sites can be downloaded in the browser and imported as files.

## Playback and storage

- Ruffle 0.7.1 is bundled locally. No CDN, Flash plugin, or native Flash runtime is required.
- A transparent WebView is created for the active wink and destroyed after playback, dismissal,
  or the activity pausing. The total lifetime is bounded to 18 seconds; playback is capped at 12 seconds.
  There is no prewarming, player pool, wake lock, or background playback. Android may retain its
  shared WebView renderer process after a player is destroyed.
- Incoming winks wait until their conversation is resumed. Pending playback is conflated to the
  latest wink for each conversation; reopening saved history does not replay old winks.
- The **Auto-play** and **Sound** switches are available in the picker and under Emoticons settings.
  Silent/vibrate mutes audio. The Android animation scale being zero uses a thumbnail.
  Explicit **Play again** can play with auto-play disabled; reduced motion still uses a thumbnail.
- Downloads reuse a matching bundled/library/cached CAB by SHA1D. History stores only references,
  not another CAB for each message. The received cache is limited to 32 MiB / 256 items per account;
  installed winks are kept separately, up to 64 MiB / 512 items. Clearing a conversation removes its
  received-cache references; installed winks and bundled defaults remain available.
- Every completed transfer is checked against the advertised byte count and SHA-1 before parsing.
  One-to-one online conversations are supported; group and offline wink delivery are unavailable.

## Protocol and package handling

Winks use `text/x-msnmsgr-datacast`, `ID: 2`, MSNObject `Type="8"`, and the `0x8000` capability.
The announcement uses MSNP MIME chunking for long original signatures. Unknown MSNObject attributes,
including `stamp`, survive storage and forwarding. A sender's Creator/Friendly/SHA1C envelope is
rebuilt for that sender while the original CAB/signature are preserved.

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
