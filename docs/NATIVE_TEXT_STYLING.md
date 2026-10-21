# Native Messenger text styling — 2026-10-21

Msngr saves a native outgoing style separately for each account and contact or group ID.
The composer's selection and cursor menus expose **Text Style**, independently of the
Messenger Plus! setting. The Material 3 modal bottom sheet contains a **Font Style**
dropdown, independent strikeout and underline switches, a current-color swatch opening the color wheel,
an integer size slider and editable size field (8–72), and Reset styling.

Font Style contains every entry in the original Messenger's Segoe UI style list: Light,
Light Italic, Semilight, Semilight Italic, Regular, Italic, Semibold, Semibold Italic,
Bold, Bold Italic, Black and Black Italic. The six base styles use weights 300, 350, 400, 600,
700 and 900, respectively; these were checked against the installed Segoe UI font files.
Android uses its system font at the corresponding weight, with the closest named system
face on Android versions before API 28.

Styles apply to subsequent messages, including notification replies, groups, offline
messages and failed-message retries. Each message retains its original style in history
chunk version 4; versions 1–3 remain readable. Clearing a draft or history leaves the
chat's saved style intact. Signing out hides that account's styles until it signs in again.

Plus! actions are grouped under **Plus! Formatting**. Its former color sheet now includes
the four decoration toggles and Clear formatting above the foreground/background palette,
gradients and custom color wheel. These actions still modify only the selected source
range. Profile fields retain the same Plus! controls; native Text Style is composer-only.
The compact status shortcut uses a modal fallback because its window has no keyboard sheet
host. Search fields expose neither formatting sheet.

## Desktop capture

Two already signed-in Windows Live Messenger 2009 sessions were used, with synthetic
messages sent through their ordinary composer and font dialog. Both processes' Winsock
traffic was recorded at send/receive, including asynchronous WSARecv completion paths.
Complete raw records remain in the ignored local `captures/native-text-style/traffic.jsonl`.
The committed fixtures under `app/src/test/resources/native-text-style/` contain complete
outgoing and incoming MSNP frames; incoming routing identifiers are replaced with test
addresses. No authentication traffic is included in these fixtures.

The captured combined-style request was (every line ending is CRLF):

```text
MSG 8426 N 154
MIME-Version: 1.0
Content-Type: text/plain; charset=UTF-8
X-MMS-IM-Format: FN=Segoe%20UI; EF=BISU; CO=80ff; CS=0; PF=22

NATIVE-STYLE BIUS orange 18pt
```

The receiving process obtained the same 154-byte MIME payload. Its `MSG` command has
sender address/display name instead of transaction/acknowledgement fields. The peer's
reply was also captured in both directions.

| Field | Observed meaning |
| --- | --- |
| `FN` | Percent-encoded Segoe UI face, including Light, Semilight, Semibold and Black variants |
| `EF` | `B` bold, `I` italic, `S` strikeout, `U` underline, in that order |
| `CO` | Hexadecimal Windows COLORREF, BGR byte order, without required leading zeroes |
| `CS`, `PF` | Charset and pitch/family; the font dialog emitted `0` and `22` |
| MSNP length | Complete UTF-8 MIME payload byte count, including all headers and CRLFs |

RGB `#FF8000` appeared as `CO=80ff`. The bold-only dialog sample emitted `EF=B`.
The initial unmodified desktop style emitted `CS=1; PF=0`; Msngr preserves its existing
exact default offline-message frame. Formatted sends use the captured dialog header.
Plus! markup remains in the message body and composes with native effects; explicit Plus!
foreground colors override the native foreground on their selected ranges.

Additional desktop probes verified that the font-style variants are transmitted through
the face name: Light uses `FN=Segoe%20UI%20Light`, Semibold uses
`FN=Segoe%20UI%20Semibold`, and Black Italic uses `FN=Segoe%20UI%20Black; EF=BI`.
Semilight Italic uses `FN=Segoe%20UI%20Semilight; EF=I`.
The complete outgoing frames are preserved as `wlm-font-*.txt` fixtures. The same header
already stored in chat preferences and message history preserves these variants without
a storage schema change. Plus! bold raises a lighter native face to weight 700 and retains
Black at 900; Plus! italics and decorations combine with the selected native style.

## Font-size limit in the native protocol

Changing only the sender's font size from 18 to 10 produced the **same format header and
154-byte MIME length**. The sender resized its entire local history; the peer retained its
own local size. WLM sends no font-size field in this protocol. Msngr therefore saves size
locally and previews it in the composer. The chat's current size applies to both incoming
and outgoing message bubbles, including existing history, without changing the sender's
other formatting. System notices keep their standard size. It does not add an invented
wire field. A recipient's font size remains under that recipient's control.
The default size 10 maps to Msngr's existing text size; other values scale it proportionally
and continue to respect Android font scaling.

The first actual size change in each chat shows a snackbar inside the styling sheet:
“Text size changes only on this device, for both incoming and outgoing messages.”
The notice flag is saved per account and contact/group, independently of the style, so
resetting formatting, reopening the chat or restarting the app does not repeat it.
Invalid or unchanged sizes and changes to other formatting do not trigger the notice.

## Verification

`NativeTextStyleTest` replays the complete captured frames at every possible byte split,
checks byte-identical payload generation, all sixteen effect combinations, RGB conversion,
malformed headers, Unicode and Plus! bodies, and group/offline framing. Repository tests
cover repeated sends, notification-style immediate sends, independent contacts/groups,
draft clearing and sign-out/restoration. Live-client contract tests check preservation on
failure and retry; history tests check restart retention.

`NativeTextStyleInstrumentedTest` exercises the real platform selection/insertion menu,
Material modal sheet, all twelve dropdown choices and rendered EditText font weights,
switches, color wheel, editable size/slider synchronization, invalid
size input, reset, actual EditText spans and preference recreation. It also applies all four
Plus! decorations through the repurposed sheet to a partial selection. Existing profile,
status shortcut, keyboard handoff, search and 256-subset rendering tests cover regressions.

Run the normal Gradle unit tests and both APK builds. Run the above instrumentation classes
on an Android emulator; optionally pass `-e styleScreenshots true` to save the native and
Plus! sheet PNGs in the debug app's external files directory. The local verification used
an isolated package ID because the emulator's existing Msngr installation has a different
signature. The existing app and its data were retained.

Final results: **1,109 unit tests passed**; debug and Android test APKs built successfully.
The latest device run passed all **8 native styling tests**, including selecting every
dropdown entry, checking the actual editor's rendered weight and italics, and restoring a
saved Semilight Italic style. It also checks resizing both directions of history while
preserving other formatting, the first-resize snackbar from both controls, and notice
persistence across reopening, reset and preference recreation.
The earlier device run passed all **21 tests** for native
styling, Plus! sheet behavior and the keyboard sheet scaffold. The separate regression run
passed the 256-subset composer
and incoming/outgoing bubble matrix, reference rendering, status shortcut, emoticon editor
and compact search checks. The earlier keyboard handoff failure was reproduced and fixed:
an editor's already queued IME show now closes the keyboard for a sheet that explicitly
keeps editor taps in its workflow, instead of dismissing that sheet.
