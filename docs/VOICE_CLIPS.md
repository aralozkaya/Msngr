# Voice clips

Msngr records, sends, receives, and plays voice clips in **one-to-one chats**.
Voice clips **do not work in group chats**. They require an online contact;
they are not sent through offline messaging. The app advertises the base
`SupportsVoiceIM` capability (`0x40000`), and does not advertise the extended
WMA or circle/group voice capabilities.

Hold the microphone beside the empty message composer to replace the input with
a recording bar and live voice-level waveform. Allow microphone access when
prompted, then hold again to start. Release to preview the clip, send it, or
delete it. Drag onto the floating lock to keep recording without holding;
tapping the lock unlocks recording and stops it if the microphone is no longer
held. Recording stops after 15 seconds and is discarded when the activity
leaves the foreground. Material motion animates recording, review, and lock
transitions. Incoming clips have separate playback surfaces and play only when
tapped. Clip audio is
stored with account-specific local conversation history and removed when that
history is erased. Interrupted downloads become an unavailable-clip message
after a restart; malformed audio is not handed to a platform media decoder.

## Native reference and capture

Verified on October 25, 2026 against the installed Escargot-patched Windows Live
Messenger 2009, version **14.0.8117.0416**. Socket sends/receives were observed
inside both running desktop clients. The recording callback substituted a
synthetic tone for microphone input. Raw captures remain private and ignored.
The checked-in WAV contains synthetic audio, and the offer fixture uses a
replacement account identifier.

The original desktop-to-desktop capture transferred a **5,460-byte**, **2.7-second**
clip in both directions of the connection. The decoded WAV was identical at
sender and receiver. An Android live probe subsequently sent a **4,060-byte**,
**two-second** synthetic clip to native Messenger. Its captured P2P payload was
byte-for-byte identical to Android's output. Re-advertising the desktop's
already recorded object made native Messenger serve that object to Android;
Android received the original 5,460 bytes unchanged and decoded them.

Both P2Pv1 and P2Pv2 transfers were observed during the Android/native exchange.
The automated tests also exercise both framing versions with the native WAV.
This establishes compatibility with this native client and Escargot, without
claiming equivalent verification on every MSNP service.

## Wire sequence

1. The sender writes a switchboard `MSG` containing MIME type
   `text/x-msnmsgr-datacast`. Its body is `ID: 3\r\nData: <msnobj .../>\r\n\r\n`.
   The object has `Type="11"`, `Creator`, byte `Size`, `Location`, `Friendly`,
   and Base64 `SHA1D` (SHA-1 of the complete WAV). Native `Location` was `0`;
   `Friendly` was a Base64 UTF-16LE temporary name. Native omitted `SHA1C`.
   Msngr preserves received object fields when requesting the object.
2. The receiver sends an MSNSLP `INVITE` inside MIME
   `application/x-msnmsgrp2p`. Its body contains:

   ```text
   EUF-GUID: {A4268EEC-FEC5-49E5-95C3-F126696BDBF6}
   SessionID: <transfer identifier>
   AppID: 20
   RequestFlags: 18
   Context: <Base64 of the NUL-terminated MSNObject XML>
   ```

   The GUID is shared with display-picture object requests; **AppID 20 and
   object Type 11 distinguish a voice clip**. SLP carries its normal To/From,
   branch, Call-ID, CSeq, Content-Type, byte Content-Length, and final NUL.
   Endpoint-aware routes include `;{endpoint GUID}` in identities.
3. The sender accepts with `MSNSLP/1.0 200 OK` and the same Call-ID and SessionID.
   Direct-connection negotiation can run alongside the object transfer.
   Msngr replies with `Listening: false` and a zero nonce, retaining the
   switchboard bridge; no inbound listening socket is required.
4. A four-zero-byte preparation message precedes the data. In P2Pv1, the
   48-byte little-endian header carries SessionID, identifier, byte offset,
   total size, fragment length, flags, and acknowledgement fields. Object data
   has flag `0x20`; the four-byte **big-endian** footer is **20** for voice.
   Completed messages receive the normal `0x02` binary acknowledgement.
5. P2Pv2 uses the big-endian main and data headers, identifier, SessionID,
   package number, and DataRemaining TLV. The first object-data fragment has
   transfer combination **5**, subsequent fragments **4**. The footer remains
   **zero**, including voice data. SYN/RAK and acknowledgement TLVs retain the
   existing bridge rules. The native capture's initial invite used operation
   **3** (SYN | RAK).
6. Fragments are reassembled with bounded sizes and ordered offsets/remaining
   lengths. The advertised size and SHA-1 must match before a clip is accepted.
   Voice data is routed to its pending object request, separately from profile
   pictures. Both participants close the object session with MSNSLP `BYE`,
   content type `application/x-msnmsgr-sessionclosebody`, and SessionID.

Clips offered by Msngr remain available to the intended conversation for ten
minutes, with a bounded in-memory cache. This accommodates native endpoints
requesting the same object. Announcing a clip successfully means it was written
to the switchboard; it is not a recipient playback or delivery receipt.

## Audio bytes

The captured RIFF header decodes as follows. All numeric WAV values are
little-endian; unknown RIFF chunks and odd-byte padding are supported.

| Field | Native capture |
| --- | --- |
| RIFF size | 5,452 (file size minus eight) |
| Form | `WAVE` |
| `fmt ` length | 20 |
| Format tag | `0x028e`, Microsoft MSN Siren7 |
| Channels | 1 |
| Sample rate | 16,000 Hz |
| Average bytes/second | 2,000 (16 kbit/s) |
| Block alignment | 40 bytes |
| Bits/sample | 0, compressed |
| Extra format size | 2 |
| Transform length | 320 samples |
| `fact` length / sample count | 4 / 43,200 |
| `data` length | 5,400 bytes = 135 frames |
| Frame duration | 320 / 16,000 = 20 ms |

The complete native file is `app/src/test/resources/voice-clips/native-siren.wav`.
`native-offer.txt` contains its sanitized datacast body. Device tests use a copy
of the same WAV. Encoding pads a final partial frame while preserving the
original sample count. Audio and format validation enforce the 15-second limit.

The separate native codec library is built from
[Youness Alaoui's libsiren](https://github.com/kakaroto/libsiren/tree/c7724c03d4a6daf4d6dff9e917c14ce5071dce66).
Its source, license, build instructions, and local bounds fixes are included in
`app/src/main/cpp/libsiren/`. The JNI bridge serializes access to upstream's
shared scratch state. Android records PCM with AudioRecord and plays decoded
PCM with AudioTrack. No Messenger DLL or server transcoder is used.

## Verification

Unit tests cover the captured offer/WAV, RIFF bounds, both P2P versions, AppID,
object access, and persistent history. Device tests exercise the actual native
codec, the 15-second maximum, malformed frames, playback UI, recording UI, and
the absent group microphone.

`VoiceClipLiveInteropInstrumentedTest` is opt-in (`-e voiceInterop true`). It
reads a private `files/voice-interop.credentials` JSON containing `email`,
`password`, and the explicitly selected desktop `peer`, and deletes that file
before sign-in. It sends a synthetic tone and waits for a native offer. Keep
the desktop client signed in for the complete transfer. The test is skipped in
normal instrumentation runs. It does not establish a local remembered login;
restore the normal app account after running live or mock tests.
