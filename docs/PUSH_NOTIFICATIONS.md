# Notifications

## In-app notifications

While any Msngr activity is in the foreground, incoming events use Msngr's banner host instead of
posting Android event notifications. This includes messages, nudges, contact requests and group
invitations. The existing background connection service keeps its required ongoing notification.
Pending request notifications are removed when entering the app and synchronized again when leaving it.

**Settings > In-app notifications** controls banners, vibration, and independent categories for
messages, group messages, nudges, contact requests and group invitations. Each type has its own named
group containing its notification and vibration switches.
All current types have paired controls, so there is no **Others** group. Everything starts enabled.
Turning off the general notification switch disables its dependent controls without changing their
selections. General settings apply to conversations that do not have a custom notification profile.
Contact sign-in alerts and general offline-message receipt notices are only shown while Msngr is in
the background. Receipt notices stay silent in the foreground even when signed in as **Appear offline**;
individual messages still follow the message notification settings.

These preferences do not create Android channels or require Android notification permission. Vibration
uses [Android's notification vibration usage](https://developer.android.com/reference/android/os/VibrationAttributes#USAGE_NOTIFICATION)
so device vibration policy applies; silent mode and Do Not Disturb suppress vibration. Bursts are
limited to one vibration per 750 ms. A banner shows the latest alert for five seconds, can be dismissed,
and opens its conversation or the requests page when tapped. Banners slide and fade in and out using
Compose's Material motion; consecutive alerts crossfade with an animated size change. The conversation currently open does
not produce banners. Incoming nudges in that conversation still vibrate when enabled; messages stay
silent. Other unread conversations contribute to the back
badge; blocked conversations do not. The back-button count is always enabled, including when alerts
are muted or turned off, and has no setting. Reading a conversation clears its banner and badge.

The contact detail notification button and **Group details > Group options > Custom notifications**
open the **Custom notifications** editor. **Use custom settings** creates a saved override from the
current general settings. Custom profiles take priority over every general in-app setting, including
the notification, vibration and banner switches. Their own switches still control their dependent
settings. Turning off **Use custom settings** deletes all saved notification overrides for that
conversation; enabling it again copies the current general settings as a fresh starting point.
In-app controls include alerts, banners, vibration and applicable categories.
Background controls separately allow muting that conversation or making its Android alerts silent;
Android permissions and channel settings still apply. The **Customized notifications** button inside
**Settings > In-app notifications** lists every override and supports editing or deleting it. Resetting restores the
general settings. Preferences are scoped to the signed-in account and service, and are removed with
the account's other preferences when the account is forgotten.

## Background notifications

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
  Failed connection attempts during session restoration use the same bounded retry budget as
  delivery failures, instead of immediately reporting that the action was not sent. Missing
  credentials and remotely revoked sessions still require sign-in; nudge cooldown rejections
  are never retried as delayed nudges.
- Incoming nudge notifications offer a **Nudge back** action through the same delivery path.
- Messages, nudges, and contact sign-in alerts use Android's Conversations API (MessagingStyle,
  long-lived conversation ShortcutInfoCompat, and matching LocusId): the contact photo
  (or contact-list initial) is the main avatar, with the app badge in its lower corner. Photos are
  supplied through the conversation person and shortcut, rather than a trailing large icon or image
  attachment. Replying from a phone notification or smart watch marks the chat read without
  dismissing the notification, keeps the incoming message(s), appends the user's reply to the
  MessagingStyle conversation, and appends any new messages that arrive while the notification is
  still expanded. Reply/nudge failure updates retain that conversation identity, including after
  process restoration. Group messages use the group's shortcut avatar and keep member names and
  avatars.
- **Contact signed in** notifications say `<contact> has just signed in.` and are sent only when
  a contact changes from Offline to Online, Busy, or Away. Changes between non-offline statuses
  do not send notifications. Returning to any Msngr screen automatically dismisses all contact
  sign-in notifications, including those left from an earlier app process. Other alerts remain.
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
