# Animated profile pictures

Msngr preserves and plays GIF display pictures in contact lists, chats, profiles, and the
full-size picture viewer. Selecting, restoring, transferring, caching, and downloading a GIF
keeps its original bytes. Other selected images continue to become 96×96 square PNGs.
Notifications and Android shortcuts use a still poster frame.

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
The original Microsoft artwork and captures are not included in the repository; regression
tests use an original two-color GIF.

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
control delays before animation. It limits inputs to 200 KiB, 1,048,576 canvas pixels, and
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
storage also advertises `image/gif` for GIF uploads.

The live check exposed an Escargot fallback problem: after native Messenger selected Soccer,
the public `static` tile still returned the account's previous PNG. Opening a profile or
polling storage could overwrite the successfully received GIF. A verified displayed GIF now
takes precedence over that fallback, including an HTTP request already in flight when P2P
finishes. A new MSNObject or explicit picture removal still permits replacement. Cached GIFs
also remain available while presence metadata is absent.

## Verification

Run `:app:testDebugUnitTest` for GIF structure/limits, both P2P versions, roaming-storage MIME,
and the static-refresh race. `AnimatedAvatarInstrumentedTest` checks distinct decoded frames,
visible animation, lifecycle pause/resume, byte-preserving selection/restoration/export, and
the still poster used by notifications. `ContactProfileInstrumentedTest` covers the existing
profile and viewer behavior.

Live verification uses the private account and native Soccer picture, compares received bytes
with the native cache, opens the contact profile, and checks the animation survives refresh.
Credentials and packet captures stay in ignored local storage.
