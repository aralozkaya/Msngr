# Background notifications

Msngr receives notification events over its direct MSNP connection. A user-visible Android
`remoteMessaging` foreground service owns the process containing that connection while the app UI
is in the background. It starts after sign-in and is enabled by default.

Android does not guarantee that a process or socket can literally never be killed. The foreground
service, `START_STICKY`, boot/package-update receiver, network callback, and automatic sign-in provide
the strongest supported client-only recovery. A force-stop always disables the app until the user
launches it again.

## Android implementation

- Android 13+ notification permission is requested once, after a successful sign-in.
- The connection service uses a low-priority, ongoing **Background connection** channel and Android's
  `remoteMessaging` foreground-service type.
- `messages`, `nudges`, `contact_signed_in`, and `offline_messages` are independent Android notification
  channels. Android's app notification settings control the general offline-receipt notice
  separately from individual message notifications.
- Message notifications offer `RemoteInput` replies and a one-tap **Nudge** action. Both are queued
  as expedited WorkManager tasks with a network constraint. Their workers use the active MSNP
  session, reconnect it, or sign back in from encrypted remembered credentials.
- Incoming nudge notifications offer a **Nudge back** action through the same delivery path.
- **Contact signed in** notifications say `<contact> has just signed in.` and are sent only when
  a contact changes from Offline to Online, Busy, or Away. Changes between non-offline statuses
  do not send notifications.
- Tapping an event notification goes through the authenticated entry point and opens the matching
  conversation, including after process death.
- Lost connections retry with capped exponential backoff. Retries suspend while Android reports that
  the network is offline and resume as soon as connectivity returns.
- Contact-picture HTTP polling stops whenever the UI is in the background, avoiding unnecessary
  periodic traffic while the persistent MSNP socket remains active.

The user can turn off **Keep Msngr Connected** in Settings. Doing so stops the foreground service
and background event delivery; reopening the app restores a normal foreground connection.

## Manual platform steps

Firebase is not used on this branch. `app/google-services.json` is intentionally ignored and is not
included in application builds.

If the app is distributed through Google Play, declare the `remoteMessaging` foreground-service use
under **Play Console > Policy > App content**. The manifest service type and permissions are already
implemented.

Users must grant the notification permission on Android 13 or newer. They may configure Messages,
Nudges, Contact signed in, Offline messages, and Background connection independently in Android settings.

## Device verification

Test on a physical device with a real community-service account:

1. Receive a message, nudge, and offline-to-non-offline presence transition with the app backgrounded and
   removed from Recents. Confirm that the ongoing Background connection notification remains present.
2. Disable each event channel in Android settings and confirm that the other channels still work.
3. Reply from a message notification with an active connection, after a forced network reconnect, and
   after process restoration with remembered credentials.
4. Toggle airplane mode and verify that reconnecting begins promptly when connectivity returns.
5. Reboot with **Remember me** and automatic sign-in enabled; confirm the background service restores
   the session without opening an activity.
6. Force-stop the app and confirm no delivery occurs until it is launched again, as required by
   Android's force-stop behavior.
