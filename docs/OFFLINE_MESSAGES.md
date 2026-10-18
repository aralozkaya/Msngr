# Offline messages

Msngr sends private text messages to offline Escargot contacts and retrieves queued messages at
login or reconnect. Text and built-in emoticon shortcuts remain available in an offline private
chat. Nudges and typing are disabled; offline circles retain their existing disabled composer.

Received messages retain the sender and original send time, increment unread counts, and use the
Android Messages notification channel, including during foreground login. Notification delivery
still follows the account's notification settings and Android permission/channel settings.

The first newly saved, unblocked message in a received batch also posts the general notice
**You received instant messages while you were offline.** on the separate **Offline messages**
channel. It appears during foreground login as well as in the background. Retries and redelivery
of messages already saved do not announce another batch. The notice uses one stable notification
ID, opens Msngr when tapped, and can be dismissed independently of conversation notifications.
Reading a chat clears its message notifications without clearing this general notice.

Disable or customize the notice in **Android Settings > Apps > Msngr > Notifications > Offline
messages**. Disabling it does not disable the Messages channel or message retrieval. Android
versions before 8.0 offer only app-wide controls.

## Protocol

1. MSNP18 submits an offline message through the notification connection:
   `UUM <transaction> <recipient> 1 1 <UTF-8 payload byte count>`. The MIME payload declares
   `text/plain; charset=UTF-8`, `X-MMS-IM-Format`, and `Dest-Agent: client`. Text is limited to
   1,200 UTF-8 bytes; a longer message produces a send error on its existing bubble.
2. Login `MSG` notifications contain `text/x-msmsgsinitialmdatanotification`; later queue
   notifications use `text/x-msmsgsoimnotification`. `Mail-Data` identifies offline messages
   (`M/T=11`), their sender, original timestamp, and server ID. A `too-large` notification also
   triggers a full metadata fetch.
3. The receiver uses the SSO credential for `messenger.msn.com` as the SOAP `PassportCookie`
   `t`/`p` fields. HTTPS requests go to the selected notification host's `/rsi/rsi.asmx`, using
   namespace `http://www.hotmail.msn.com/ws/2004/09/oim/rsi`.
4. `GetMetadata` returns the XML message list as an escaped string. Both escaped SOAP metadata
   and plain notification XML are accepted. The exact server ID, including casing, is preserved
   in `GetMessage` and `DeleteMessages`; Escargot uses that ID as a case-sensitive storage key.
5. `GetMessage` uses `alsoMarkAsRead=false`. The returned MIME body supports base64,
   quoted-printable, and unencoded text, including encoded sender display names. A sender mismatch,
   unsupported payload, SOAP fault, or invalid response leaves the server copy intact.
6. `DeleteMessages` runs only after successful account-scoped local storage. Message IDs derive
   deterministically from the server UUID, so a failed deletion or repeated login cannot duplicate
   the saved message or raise another unread event. UUID casing does not change the local ID.

The worker retries failed downloads, storage, or deletion after 30 seconds while that session
remains active. Logout/account changes cancel it. A response from an abandoned session cannot
be acknowledged or saved under another account. Retrieval failures do not disconnect the main
MSNP session. Debug diagnostics contain HTTP status, counts, failure stages, and exception types;
they omit tickets, sender identities, and message contents.

Offline messages can predate existing history pages. Their storage transaction rewrites the
conversation in chronological order, flushes staged chunks, and swaps the directory with a
recoverable backup. Existing unreadable history or a failed disk write prevents acknowledgement.

## Delivery limits

`UUM` has no success acknowledgement. The outgoing bubble records socket submission, and a
correlated numeric server rejection updates its error state. Escargot can silently drop a message
when the recipient is already online or blocks the sender; submission cannot prove final delivery.
If an online switchboard invitation explicitly returns error 217 before text submission, Msngr
can safely send through `UUM`. An ambiguous timeout or an error after text submission does not
trigger another automatic send.

Live interoperability was verified on Escargot. Other community services must expose compatible
MSNP18 and RSI endpoints; this verification does not establish their offline-message behavior.

## Testing

MsnpOfflineMessagesTest and LiveOfflineMessagesTest use synthetic messages and in-memory
transports to cover MIME decoding, timestamps, persistence failures, retries, and deduplication.
OfflineContactComposerInstrumentedTest and OfflineMessageNotificationInstrumentedTest cover
Android behavior without signing in to a community service.
