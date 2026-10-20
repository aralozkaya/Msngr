# Messenger Plus! combination verification

The matrix treats bold, italic, underline, strikeout, solid foreground, foreground
gradient, solid background, and background gradient as eight independent effects.
It includes all **256 subsets**, including a solid color enclosing a gradient in
the same channel. Reversing the nesting also checks the solid override inside a
gradient. Colors are fixed; palette enumeration is covered by the earlier native
reference fixtures.

`MessengerPlusCombinationTest` checks all **109,601 ordered subsets**, **4,096
mixtures** of BBCode, binary, and readable decoration controls, selection toggles,
mixed selections, and repeated outgoing transformations. The Android rendering
test checks the actual EditText spans and both MessageBubble directions for every
subset in forward and reverse order. Its second test exercises Select all in the
native message editor.

## Live interoperability

The live test is opt-in. It uses the application's real ChatActivity, repository,
network transport, message composer, send button, and received/sent bubbles. It
does not run against a mock server. Use two test accounts: one signed in to the
installed Windows Messenger/Plus! client and another for Android.

1. Back up the installed **Msngr Compatibility Probe** script. Run
   `Prepare-NativeInterop.ps1` with its script directory. This produces 32 batches
   of eight subsets in `app/build/plus-interop/ui-batches.json`, and installs the
   read-only receiver/oracle script. Reload it in Plus!'s script editor. The
   preparation script preserves an existing `Probe.js` as
   `Probe.js.before-interop` on its first run.
2. Put `interop-config.json` in that script directory, encoded as UTF-16 JSON,
   with a `peer` field containing the Android test account's email. The script
   records only synthetic `PLUS-TX-` messages from that single contact. It never
   sends messages. Its `interop-oracle.json` contains native RemoveFormatCodes
   results for the generated inputs.
3. Supply private UTF-8 JSON named `plus-interop.credentials` with `email`,
   `password`, and `peer` (the desktop account). Push it to `/data/local/tmp/`,
   then copy it into the debug app's private `files/` directory with `adb shell
   run-as org.aralozkaya.msngr cp /data/local/tmp/plus-interop.credentials
   files/plus-interop.credentials`. Never commit this file.
4. Install the debug and Android test APKs. Start:

   ```text
   adb shell am instrument -w -r -e plusInterop true -e class org.aralozkaya.msngr.MessengerPlusLiveInteropInstrumentedTest org.aralozkaya.msngr.test/androidx.test.runner.AndroidJUnitRunner
   ```

5. Wait for `PLUS-BATCH-READY` in the desktop conversation. Send each of the 32
   batch strings through the desktop client's normal typing field to Android.
   Each batch is under 800 characters. Confirm it appears in the desktop history
   before entering the next. The test waits up to ten minutes for these messages.
6. The test then verifies every received source and rendered style, composes each
   subset, checks its editor spans, and sends it back through the app's send
   button. It checks the resulting outgoing bubble and network delivery result.
   Verify all 256 `PLUS-TX-` IDs in the desktop client's history and receiver log.
7. Pull `messenger-plus-interop.json` from the app's external files directory.
   Each row records receiving, composing, and sent-bubble results. Native Plus!
   masks consumed tags with NBSP in the receive-script callback; these are not
   visible spacing in the rendered history. Ignore these masking characters only
   when comparing the synthetic callback text with the expected plain text.
8. Remove all temporary credential files and restore the original script and its
   previous running/stopped state. The Android test logs out and removes its
   private credential file on completion.

The batch arrival checks exclude saved history from earlier runs. The unit and
device tests need no live accounts; the live test is skipped without
`-e plusInterop true`.
