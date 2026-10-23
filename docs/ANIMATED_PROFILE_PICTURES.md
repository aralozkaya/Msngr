# Animated display pictures

Msngr preserves the original PNG, JPEG, BMP, or GIF when it fits the native Messenger user-tile
budget of **less than 200 KB (at most 199,999 bytes)**. It does not crop static pictures to 96×96, upscale small
pictures, or recompress compatible originals. GIFs animate in contact lists, chats, profiles,
and the full-size viewer. Notifications and Android shortcuts use a still poster frame.

## Selection, quality, and download requirements

### Incoming display pictures and the local copy

Sign-in downloads the account's own picture from Messenger's authenticated roaming
profile service. Foreground launches also refresh it when an MSNP session is already
running in the background. The app compares the complete image bytes with an atomic,
account-specific local snapshot (email plus notification service), rather than comparing
URI strings or thumbnails. Original GIF animation remains intact in both previews.

On the first successful picture fetch, use the server picture directly, including a
profile with no picture. Later differences follow **Settings → Connection → Display
picture changes**: **Always use incoming one**, **Always use local one**, or **Ask every
time** (default). The choice modal shows the old local picture on the left, an arrow,
and the incoming picture on the right. **Keep local** publishes the saved original
back to Messenger; **Use incoming** adopts the downloaded original without uploading
it again. **Not now** retains the local snapshot and asks again on a later launch.
Identical bytes never prompt, even if their URIs differ. A failed/invalid download is
not a picture removal and does not erase or initialize the local snapshot.

Background session restoration preserves unresolved choices until the foreground UI
can show them. Upload failures keep the old snapshot and allow retrying the choice.
Explicit local/server removals are valid choices. Account switching discards stale
fetch results, and forgetting an account removes its snapshot and sync preference.

### Choosing a picture

The display picture editor offers **Take a photo**, **Select a photo**, and
**Use a default photo**, in that order. The Android photo picker requests `image/*`
so GIFs remain selectable; AndroidX falls back to the system document picker on
devices without a photo picker. Full-resolution camera captures use a dedicated
temporary FileProvider directory, normalize EXIF orientation, and are deleted on
success, cancellation, or read failure. Every source uses the existing inspection,
resize-consent, draft, and save flow.

The default-photo modal bundles all 45 unique originals listed in the source wiki's
display-picture tables: 11 from 6.1–8.5, 30 introduced in 2009, and 4 introduced in
2011–2012. Nine are animated GIFs; none of the source display pictures are APNG.
The modal groups additions by era, previews animations, and requires **Use photo**
before changing the draft. Cancelling preserves the previous picture. Bundled images
work offline, and their original bytes survive selection, restore, and export.
See `app/src/main/assets/display_pictures/README.md` and `catalog.json` for source
URLs, credits, and hashes. Re-run `python tools/download_display_pictures.py` to
reproduce the collection. Scenes and backgrounds are excluded.

| Property | Behavior |
| --- | --- |
| Static formats | PNG, JPEG, BMP, and single-frame GIF; detected from bytes, then decoded |
| Animated format | GIF87a/GIF89a; unsupported animation formats are rejected, never flattened |
| Upload budget | 199,999 bytes, shared with P2Pv1, P2Pv2, and direct object downloads |
| Dimensions | Preserve the original aspect ratio and dimensions; 96×96 is a native thumbnail convention, not a wire constraint |
| Oversized selection | Show source dimensions and size, then offer **Resize and continue** or **Cancel** before changing the draft |
| Static resize | Filtered scaling, no crop, no enlargement, lossless PNG output |
| Animated resize | Composited full frames, adaptive palettes (256 colors, or 255 plus transparency), GIF LZW, original frame count, delays and loop extension |
| Resize budget search | Binary search over the longest edge for the largest fitting candidate; compressed sizes can be non-monotonic, so this is not a mathematical global optimum |
| Preview/restoration | Use the prepared upload bytes, including after saving/reopening the profile |
| Download | Export the complete original bytes with their actual MIME type and extension, independent of the UI bitmap/thumbnail |

Input reads are bounded to 32 MiB and 16 megapixels; GIFs also have a 500-frame and
128-million total source-pixel work budget. Playback/upload canvases are limited to
1,048,576 pixels. These are explicit application resource guards, not additional MSNP wire
requirements. A larger decodable canvas requires the same resize consent. Files exceeding the
source guards receive an actionable error. A new source is never modified on disk.

Native Windows Live Messenger 14.0.8117.0416 was checked on 20 October 2026. Its chooser
accepted a valid 199,999-byte GIF and rejected the same GIF padded with valid comment blocks
to 200,000 bytes, with the explicit “smaller than 200 KB” message. A
192×192 GIF of 8,643 bytes was accepted and stored byte-for-byte in `ObjectStore/UserTile`.
The supplied `knock-knock-knock.gif` is valid GIF89a, 416×326, 39 frames, 1,850,182 bytes.
The Android resize path produced **113×88, 39 frames, 197,204 bytes**, preserving all frame
delays. The source file remains private and is not bundled or committed.

Native WLM's MSNSLP `Context` contains Base64 XML followed by a NUL byte. Receivers must
strip the terminator before XML parsing; rejecting it caused otherwise valid native download
requests to be declined. Both P2P versions share this context validation.

P2Pv2 picture data uses a zero bridge footer; the application-ID footer belongs to P2Pv1.
Native WLM can move an existing P2P session to another switchboard and keep that session
after closing a switchboard. Msngr retains its identifiers, pending requests, and partial
objects per remote endpoint, rebinds outgoing packets to the current connection, and lets
the previous receiver finish its remaining packets. All connections close on sign-out,
blocking, or contact removal. A manual/initial refresh still retries the announced original
when public storage supplies only a stale or smaller fallback.

Escargot's legacy presence generator advertises the MSNObject from the **first signed-in
session** of an account. When that account is also running in native WLM, later app uploads
can be masked by the older native object's metadata. A live upload probe must account for
this server behavior; successful storage submission alone does not prove native receipt.

## Native Windows Live Messenger Soccer investigation

Verified on 18 October 2026 with Windows Live Messenger 14.0.8117.0416, Escargot, and the
Android emulator signed in using the repository's private `.env` account. The second signed-in
native account supplied the contact picture.

| Property | Observed value |
| --- | --- |
| Encoding | GIF89a, indexed color with LZW-compressed frames |
| Canvas | 96×96 pixels |
| Frames | 39, including smaller delta-frame rectangles |
| Timing | 100 ms per frame; 3,900 ms per cycle |
| Length | 57,148 bytes |
| SHA-1, Base64 | `mK7Sb6JCiac11q2DUIMFewTl/6g=` |
| Native cache | `ObjectStore/UserTile/*.dt2` containing the GIF bytes directly |

Soccer is a regular animated GIF display picture. The `.dt2` suffix does not imply a separate
image codec. This change does not implement the separate CAB/Flash dynamic-content system.
The default-picture collection now includes the wiki's original Soccer GIF. Private
captures remain excluded; protocol regression tests use an original two-color GIF.

## Transfer on the wire

The emulator's Wi-Fi interface was captured with its built-in `tcpdump`, filtering TCP ports
1863 and 1864. TCP streams were reassembled before parsing MSNP frames and binary P2P payloads.
Host administrator privileges were unnecessary.

1. `NLN` presence advertises a URL-encoded `<msnobj Type="3" ...>` with `Size="57148"` and the
   SHA-1 above. GIFs use the same display-picture object type as PNGs. The native object also
   contains the creator, location, a Base64 UTF-16 friendly name of Soccer, and `contenttype="D"`.
2. Msngr opens the switchboard and sends a P2Pv2 MSNSLP `INVITE`, addressed to the native
   endpoint. Its session request uses EUF-GUID `A4268EEC-FEC5-49E5-95C3-F126696BDBF6`, `AppID: 1`,
   `RequestFlags: 18`, and a Base64 MSNObject in `Context`.
3. Native Messenger accepts the session. Direct transport negotiation falls back to the
   switchboard bridge. A four-byte preparation message precedes the object.
4. The GIF arrives in 47 P2Pv2 data packets, with first-fragment/MSNObject flags and decreasing
   `DataRemaining`. Reassembling their bodies yields exactly 57,148 bytes. Both the advertised
   SHA-1 and a byte-for-byte comparison with the native `.dt2` file match.

No GIF-to-PNG conversion, animation side channel, or additional animation download is involved.
The existing P2Pv1 and P2Pv2 sessions already transfer and hash-validate the complete file.

## Decoding and refresh behavior

`GifDisplayPicture` inspects the GIF structure, logical canvas, frame rectangles, and graphic
control delays before animation. It limits inputs to 199,999 bytes, 1,048,576 canvas pixels, and
500 frames. Pixel decoding, transparency, LZW decompression, and frame disposal belong to the
platform decoder:

- Android 9+: [`ImageDecoder.decodeDrawable`](https://developer.android.com/reference/android/graphics/ImageDecoder)
  creates an `AnimatedImageDrawable`. A Compose painter forwards invalidation and scheduling
  callbacks and loops the display picture.
- Android 7–8: [`Movie`](https://developer.android.com/reference/android/graphics/Movie) renders
  frames on a per-avatar timeline using the parsed GIF delays.

Each visible instance owns its animation state. Playback stops when its lifecycle stops or
it leaves composition. Static poster bitmaps remain shared through the existing bitmap cache.
The full-size viewer exports GIF bytes as `image/gif` with a `.gif` filename. Roaming profile
storage advertises the actual detected MIME type for every supported format.

The live check exposed an Escargot fallback problem: after native Messenger selected Soccer,
the public `static` tile still returned the account's previous PNG. Opening a profile or
polling storage could overwrite the successfully received GIF. A verified displayed GIF now
takes precedence over that fallback, including an HTTP request already in flight when P2P
finishes. This also protects a verified static original from a smaller/stale fallback while
its MSNObject remains current. A new MSNObject or explicit picture removal permits replacement. Cached GIFs
also remain available while presence metadata is absent.

## Verification

Live interoperability was checked on 20 October 2026 against the two running native WLM
instances. The native instance sharing the app's account was temporarily signed out to avoid
Escargot's first-session metadata rule. The other instance displayed the app's 512×384 PNG
(4,605 bytes), 512×384 JPEG (97,536 bytes), and the resized attachment (113×88 GIF, 39 frames,
197,204 bytes). Two native observations showed different GIF frames. Reassembled outgoing
P2P object bytes matched the input files; incoming/exported native Soccer bytes matched its
57,148-byte original. No private image or credential is bundled with these tests.

Run `:app:testDebugUnitTest` for GIF structure/limits, independent ImageIO decoding of the GIF
encoder (including LZW dictionary growth/reset, transparency, palette quality, timing, and
loops), both P2P versions, native NUL-terminated requests, roaming-storage MIME, and both
static/animated refresh races. `ProfilePictureInstrumentedTest` covers consent, resize,
original PNG/JPEG bytes, MIME, restore, and export. Its optional private GIF test runs when
`files/profile-picture-validation/knock-knock-knock.gif` exists in the debug app.
`AnimatedAvatarInstrumentedTest` checks distinct decoded frames,
visible animation, lifecycle pause/resume, byte-preserving selection/restoration/export, and
the still poster used by notifications. `ContactProfileInstrumentedTest` covers the existing
profile and viewer behavior.

`DisplayPicturePickerInstrumentedTest` verifies every bundled hash and decoded image,
all nine GIFs' distinct frames and byte-preserving restoration/export, the three
source actions, version filters, preview/confirmation/cancellation, draft removal,
Android's image-picker contract and GIF result, full-resolution camera output,
EXIF orientation, and capture cleanup.

`DisplayPictureSyncPolicyTest` covers all three incoming-picture policies, first
login, identical byte content, removals, and unavailable fetches. The own-picture
contracts in `LiveMsnp18ClientContractTest` verify fresh roaming downloads, adopting
the incoming MSNObject without re-uploading it, keeping a local object after the
remote storage content changes, and distinguishing a failed fetch from removal.
`DisplayPictureSyncInstrumentedTest` covers durable original bytes, account
isolation, upload retries, deferred choices, first-login behavior, and the setting
and comparison UI. `DisplayPictureSyncLaunchInstrumentedTest` exercises the actual
authenticated MainActivity launch, background/foreground return, and resolved
picture restoration with the mock server. These tests do not use live credentials.

Live verification uses the private account and native Soccer picture, compares received bytes
with the native cache, opens the contact profile, and checks the animation survives refresh.
Credentials and packet captures stay in ignored local storage.

`ProfilePictureLiveInteropInstrumentedTest` is explicitly opt-in (`-e pictureInterop true`).
It reads a private `files/profile-picture-validation/live.credentials` JSON with `email`,
`password`, and `peer`, plus `static-original.png`, `static-original.jpg`, and
`knock-resized.gif` fixtures. It publishes each for 60 seconds (overridable with
`-e picturePhaseMillis`; use `-e pictureFixtures knock-resized.gif` for a single fixture)
and exports the peer's native GIF. Compare native receipt and the
reassembled P2P object with each fixture separately; the probe itself does not
assert native receipt. It restores the prior roaming picture and removes its credential file.
