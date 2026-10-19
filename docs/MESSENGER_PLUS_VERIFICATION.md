# Messenger Plus! verification — 2026-10-19

Verified against the installed Windows Live Messenger with Messenger Plus!
**5.02.0.712** and the Android API 37.1 emulator. The desktop client sent the
receive fixtures through its normal typing field. Android returned every subset
through the real ChatActivity composer and send button; all 256 return IDs and
their plain text were read from the desktop client and its native receiver hook.

## Coverage and results

Final verification: **1,088 unit tests passed**, both APK builds succeeded, the
256-subset Android rendering test passed, the native-editor Select all regression
passed, and the opt-in live test passed. The live test took 911.575 seconds,
including entry of the desktop batches and the return exchange.

| Check | Cases | Result |
| --- | ---: | --- |
| Every subset of the eight formatting effects | 256 | Pass |
| Every nesting order of every subset, including repeated outgoing processing | 109,601 | Pass |
| Every mixture of BBCode, binary and readable decoration controls | 4,096 | Pass |
| Android composer and incoming/outgoing bubble spans, forward/reverse nesting | All 256 subsets in both orders | Pass |
| Desktop → Android receiving: original source and actual bubble styles | 256 subsets in 32 messages | Pass |
| Android composing: actual EditText font, decoration and color spans | 256 | Pass |
| Android sent bubbles and delivery results | 256 | Pass |
| Desktop receipt: unique IDs, Unicode text and rendered history | 256 | Pass |
| Select all and toggle, including combined styles and colors | Every applicable subset/decoration pair | Pass after fix |
| Mixed-selection toggles and formatting restoration outside scopes | All decorations / all subsets | Pass after fix |

The eight effects are bold, italic, underline, strikeout, solid foreground,
foreground gradient, solid background, and background gradient. Solid and
gradient colors in the same channel are included together. Canonical nesting and
reverse nesting exercise both override directions. Colors are fixed to
`#C00000`, `#FF0000 → #0000FF`, `#FFFF80`, and `#FFFF00 → #00FF00`.

The live cross-client exchange uses canonical nesting. The complete set of
109,601 orders is checked by the codec tests; forward/reverse orders also run on
the Android editor and bubble components. This separates the coverage levels
without equating automated Android span assertions with desktop pixel comparison.

The [per-subset CSV](messenger-plus-combination-results.csv) records every effect
flag and the four live outcomes. The 512 captured receive/send plain-text results
are saved in
`app/src/test/resources/messenger-plus/native-5.02.712-chat-subsets.tsv` and replayed
alongside the earlier 282 native fixtures. Existing palette, malformed input,
Unicode, literal-region, reset, gradient-position and source-mapping tests remain
part of the unit suite.

## Bug fixed

Selecting all text containing hidden formatting tags placed the selection start
before the opening tag. The formatting controller inspected that source boundary
and treated the selection as unformatted. Toggling an existing decoration could
therefore add another tag instead of removing it. Mixed selections also used only
the first character's style.

The controller now derives active decorations from all visible glyphs in the
selection. A decoration is active only when it applies throughout, so Select all
can remove it and a mixed selection can apply it consistently. Unit regressions
and the actual native Android editor cover every applicable subset/decoration
pair.

The exhaustive matrix did not expose an additional encoding, decoding, gradient,
or bubble-rendering mismatch. A quoted closing-tag edge case was compared with
the desktop client and preserves native Plus!'s unusual scope-pairing behavior.

## Reproduction

See [the live test procedure](../tools/messenger-plus/README.md). Regular checks:

```text
gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb shell am instrument -w -r -e class org.aralozkaya.msngr.MessengerPlusCombinationInstrumentedTest org.aralozkaya.msngr.test/androidx.test.runner.AndroidJUnitRunner
```

The live test requires explicit `plusInterop=true` and separate test accounts.
It is skipped in ordinary instrumentation runs. Temporary credentials were
removed and the desktop probe was restored to its original script and stopped
state after verification.
