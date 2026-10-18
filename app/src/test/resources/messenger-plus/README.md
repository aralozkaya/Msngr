# Messenger Plus! native reference

The reference is the installed Windows Live Messenger with Messenger Plus!
**5.02.0.712**. Tests were gathered on 2026-10-04 using a local Plus script:
`MsgPlus.RemoveFormatCodes(source)` supplied the plain-text results, while
`RichStaticControl` displayed the same inputs for visual comparison. The script
did not send messages or save changes to the account's name or personal message.

`native-5.02.712-plain.tsv` contains 282 captured results. Each row contains a
descriptive name, Base64-encoded UTF-8 input, and Base64-encoded UTF-8 output.
Encoding keeps control bytes, tabs, newlines, and Unicode intact. The palette
JSON contains the 68 RGB entries from this version's native palette lookup;
its values were also checked against the rendered palette examples.

The text fixtures cover all control bytes from U+0000 through U+001F, palette
indices 0–99, all native named colors, malformed color arguments, legacy nickname
controls, matched and unmatched tags, crossing and nested tags, literal regions,
foreground/background gradients, whitespace, and Unicode. Text fixtures alone
do not validate colors or font styles. The codec and Android rendering tests
separately check the styles established by the native visual comparisons.

## Observed rules

| Effect | Binary control | Readable nickname control | BBCode |
| --- | --- | --- | --- |
| Bold | U+0002 | `·#` | `[b]…[/b]` |
| Italic | U+0005 | `·&` | `[i]…[/i]` |
| Underline | U+001F | `·@` | `[u]…[/u]` |
| Strikeout | U+0006 | `·'` | `[s]…[/s]` |
| Color | U+0003 | `·$` | `[c=color]…[/c]` |
| Background | comma after color control | comma after `·$` | `[a=color]…[/a]` |
| Reset binary/readable styles | U+000F | `·0` | No equivalent reset tag |
| Literal BBCode | — | — | `[n]…[/n]` |

- Binary/readable styles toggle independently. A bare color control resets both
  colors; reset-all also clears the four font attributes. Enclosing BBCode remains
  active after a binary reset. Active binary colors take priority over BBCode
  colors regardless of which starts first, and survive closing BBCode tags.
  A color reset resumes an enclosing color or gradient.
- BBCode is case insensitive and requires matching tags. Nested styles restore
  the outer state. Unknown tags and unmatched tags remain visible.
- Native gradients use `[c=start]…[/c=end]` and `[a=start]…[/a=end]`. `[g=…]`
  is literal text. A single-character gradient uses the start color.
- Gradient progress uses UTF-16 positions in the original body, including nested
  tags, binary controls, spaces and newlines. In the native comparison,
  `[c=#000000]AA[b]BB[/b]CC[/c=#FFFFFF]` matched the swatches for source positions
  0, 1, 5, 6, 11 and 12 in a 13-unit body, rather than six consecutive positions.
  Inner colors override the outer ramp locally; the outer ramp continues behind
  them. Foreground and background ramps are independent.
- Palette IDs contain one or two ASCII digits; IDs 68–99 resolve to black.
  Hex colors require six digits after `#`. BBCode ignores trailing characters
  after those six digits. Binary colors consume exactly six digits.
- Binary/readable color controls support indexed or hex foreground and
  background values, including background-only values such as U+0003`,#123456`.
- Names: white, black, red, yellow, pink, green, orange, purple, blue, beige,
  brown, teal, navy, maroon, limegreen, gray, and adapt. `blue`, `green`, and
  `gray` do not have the same RGB values as similarly named palette entries.
  Invalid BBCode color arguments inherit the surrounding color. `adapt` follows
  Messenger's scene on Windows; the Android renderer resolves it from the
  Material primary color, including when it is a gradient endpoint.
- `[n]` quotes BBCode, but binary/readable controls remain active. Native literal
  regions do not nest normally: processing from the right pairs a closing tag
  with the nearest preceding opener. The fixtures preserve this behavior.
- U+0000 terminates a native string. U+0016 and U+001D are **not** native
  strikeout and italic controls and are preserved as text.

The formatting editor emits the same paired tags for messages and profiles.
The send pipeline preserves them so repeated processing cannot reinterpret
quoted tags or change gradient positions. Source/display boundary maps are
checked separately to preserve selections and emoticon ranges.

## Verification

Run `:app:testDebugUnitTest`, `:app:assembleDebug`, and
`:app:assembleDebugAndroidTest`. Emulator coverage is in
`EmoticonUiInstrumentedTest` and `MessengerPlusRenderingInstrumentedTest`.
The rendering test also saves
`messenger-plus-reference.png` in the app's external files directory for visual
inspection.

Incoming UBX personal messages decode named, decimal, and hexadecimal XML entities
once before Plus formatting. The synthetic test fixture checks that `[c&#x3D;04]Msngr - Coming to Android[/c&#x3D;12]` status becomes
`[c=04]Msngr - Coming to Android[/c=12]`, with 25 distinct foreground colors.
Unit tests cover this payload, Unicode, binary controls, invalid numeric entities,
and escaped literal entities that must not be decoded twice.

The archived official scripting SDK describes the three native code families
in `Ref-MsgPlus-RemoveFormatCodes.htm` and the rendering control in
`PlusInterface.xsd`. An archive is linked from the
[Escargot SDK archive page](https://www.escargot.chat/services/msn/forums/110002000195600/messenger-plus-live-scripting-skinning-documentation-downloads/).
