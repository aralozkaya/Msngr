# Messenger Plus! Emotion Sounds

Hold the emoticon button in a conversation and choose **Emotion sounds**. The picker uses
the same keyboard-aware sheet as emoticons, including an expandable height and an internal
search field that can coexist with the keyboard. It works in private and group conversations.
It does not check recipients' client capabilities: other clients can display the text fallback.

**My sounds** contains the 17 native built-in sounds and downloaded sounds saved to this
account. Tap a name to send; the play button previews it. Sound names occupy one line, with
ellipsis for overflow; long-press or hover over a name to show it in full in a tooltip. Rows use
narrow play and download/remove buttons and omit sound-ID subtext. **Online** offers Escargot's published
popular sounds, with name/ID filtering, previews and download buttons. Enter any 12-digit
sound ID (optionally starting with `#`) to download it directly. The category menu uses Material 3
Expressive grouped items and selection shapes. **Random** opens a dialog where you can choose
**Any language** or one of the 24 native Plus! sound languages, then download a candidate in the
selected category (or a random category for **Any category**). Preview, try another, **Keep**, or
**Discard** it; only **Keep** adds it to My sounds. Closing the dialog cancels its work and stops
preview playback. Language IDs match the installed native `Sounds.Languages` / `Lang01`–`Lang24`
table.
The native quick panel's name search also filters its local collection; the Plus! service does
not define a remote full-database name-search endpoint.

Tap a sound message to open a standard Material bottom sheet with Play/Stop and, for custom
sounds, Add to saved sounds / Remove from saved sounds. Removing a saved sound retains its
bounded replay cache. Failures appear in the sheet and another Play retries the download.

**Play emotion sounds automatically** is under **Messenger Plus!** settings. It is independent
of text rendering. New sound messages wait for their conversation to be resumed, with only
the latest pending sound per conversation retained; messages older than a minute are skipped.
History does not autoplay. Sending a sound queues local playback after successful delivery.
Explicit previews and Play work with autoplay off. Playback stops on activity pause,
dismissal of its playback sheet, audio-focus loss, or replacement by another sound. It uses
media volume, transient audio focus and the platform MP3 decoder; there is no background player.
Files are limited to 1 MiB and playback to 15 seconds (with a 20-second preparation/lifetime limit).

## Services and presets

The installed Escargot-patched `MsgPlusLive.dll` contains
`http://msn.bart.escargot.chat/esnd/snd/`. Requests from the desktop clients confirmed this
base. Escargot's [public sound page](https://escargot.chat/sounds/) uses the equivalent
`https://bart.nina.chat/esnd/snd/` service, which Msngr uses with valid HTTPS certificates.
The desktop hostname's HTTPS certificate does not match that hostname; certificate checking
is retained and no TLS exceptions are added.

The [CrossTalk Reroute source](https://git.ugnet.gay/CrossTalk/reroute) and its
[published configuration](https://storage.ugnet.gay/crosstalk-dist/client/all/patching/reroute/sample-reroute.ini)
were inspected: they do not contain Plus! sound URL replacements. The CrossTalk preset therefore
uses the same restored public sound library, independently of its MSNP endpoints. This is an
app default, not a claim of a dedicated CrossTalk sound server. Sound IDs belong to their sound
service and need not exist on another service.

**Custom service → Emotion sounds service URL** accepts a complete HTTP or HTTPS base URL,
including its path. The exact configured base is used for built-in, custom and random downloads;
it is never derived from a message or from the MSNP server hostname. Custom services support
local-library search, ID downloads and random downloads. The Escargot popular catalog is shown
only for the known Escargot sound-service base. Existing account records migrate to the public
service without changing their credentials, saved-login preferences or account identity.

The library and replay cache are isolated by account and sound-service URL. Saved sounds and
cached sounds each have a 32 MiB / 256-file limit. History keeps the original text and native
font header, rather than embedding audio for every message. Received files must contain MP3
frames; custom files must also carry matching Plus! ID metadata, file length and audio CRC32. Filenames use validated IDs,
never sound titles. Downloads have 15-second connect/read timeouts and do not follow redirects.

## Desktop protocol research

Two already signed-in Windows Live Messenger 2009 / Plus! clients exchanged built-in,
custom and custom-styled built-in sounds. Full ordinary-message frames were recorded at send
and receive. The local DLL was also disassembled to verify the command-to-code table,
sound-marker range and custom envelope parsing. Its SHA-256 was
`35d7a646901961f2f67eb5ea675aa0b2e627021f601e2e06fcb7e7fd55068123`.

Built-in sounds are `text/plain; charset=UTF-8` with `U+0004` followed by a single character
whose code is the sound number. For example, Hello is decimal 35 (`#`):

```text
\u0004#\u00037Hello!\u0003
\u0004#[b][c=#FF6600]Styled sound[/c][/b]
```

The first is the native default caption using binary Plus! color controls; the second was
sent through `/shello` with a custom caption. Both retain the ordinary `X-MMS-IM-Format` header.
Msngr removes only the two-character sound marker for display and passes the remaining source
through its existing Plus! and native-font renderer. Incoming source and history remain intact.

Custom announcements use this ordinary text body (`NBSP` shown explicitly):

```text
[Messenger Plus! Sound] - Data{3224D7DF910D}<NBSP>-<NBSP>CHEN<NBSP><40 ASCII spaces>
```

The native parser expects a 12-digit hexadecimal ID and at least 64 characters overall.
All send layers preserve the native padding, including the switchboard's text sender.
Msngr renders the title through the same styling path and retains the complete envelope.
Group delivery uses normal circle Text messages and preserves sender attribution and the
native font header. No datacast or P2P audio transfer is involved.

Observed service operations are `builtin?code=35`, `check?hash=<ID>` before native sends,
`get?hash=<ID>` and `random?catId=1` (optionally `&lngId=<language>`). A missing sound is the
single byte `0`. Native custom downloads are MP3 files whose ID3v1 title holds the name,
the first year byte holds the category, and the comment contains `MsgPlus! - #<12-digit ID>`.
The final genre byte stores the language. Msngr downloads a sound before offering to send it,
so another check request is unnecessary; it does not upload or author new sounds.
The first four hexadecimal ID digits are the low 16 bits of the complete file length, and
the remaining eight are CRC32 of the file excluding its final 128-byte ID3v1 tag. This was
verified against six complete real-service downloads, including the native CHEN example.

## Verification

`EmotionSoundTest` replays the sanitized complete desktop frames at every byte boundary,
checks both styling syntaxes, all 17 built-ins, custom padding, group/offline framing,
MP3 metadata/error handling, configured URLs, catalog parsing, account isolation and history.
Live-client contract tests exercise switchboard delivery and live autoplay queue consumption;
circle tests exercise real group Text packets with in-memory transports and unknown capabilities.
Account-codec tests cover migration and round-tripping the new URL.

`EmotionSoundInstrumentedTest` checks the group composer menu, keyboard-aware search and send,
styled text bubbles, native playback sheet, actual platform audio playback, pause cleanup,
preference persistence, and real-service random/ID downloads plus saving/removing/replaying.
`RandomEmotionSoundInstrumentedTest` verifies native language-ID requests, preview without caching,
explicit Keep/Discard, clearing candidates when filters change, failed-download retries without
autoplay, and discarding responses that arrive after the dialog closes.
Pass `-e emotionSoundScreenshots true` for screenshots containing only synthetic test UI.

The fixture README records provenance. Desktop group delivery and physical-device power usage
have not been measured; packet tests and emulator playback do not establish those results.

Local verification on 2026-10-29 passed all **1,216 unit tests**, debug/release/test APK builds,
and **60 distinct Android instrumentation tests**, including all four new sound tests against
the real sound service. The existing
`MessengerPlusColorSheetInstrumentedTest.profileFieldsTrailingEmoticonButtonsOpenThePanelAndInsertEmoticons`
fails at its display-name insertion assertion on both this change and unchanged commit
`dd43670`; the baseline used an isolated application ID and preserved the main app's data.
Wink and keyboard-sheet regressions passed together in a 25-test run. The wider 40-test
styling/color-sheet/voice-clip/sound run passed 39 tests with that single baseline failure;
the final focused run again passed all four sound tests and reproduced the profile failure.

The initial native-to-native captures completed. An additional Android-to-desktop live
exchange could not complete after the desktop client processes ended. The opt-in
`EmotionSoundLiveInteropInstrumentedTest` remains available for a future signed-in peer;
it requires `-e emotionSoundInterop true` and a private `files/emotion-sound-interop.credentials`
JSON containing `email`, `password` and `peer`, which it deletes before connecting.

After instrumentation, the local main app was rebuilt and reinstalled without clearing its
data. The repository's local restore helper saved the real account, enabled saved credentials
and automatic sign-in, and verified a cold launch reaches `MainActivity`. The emulator was
left running.
