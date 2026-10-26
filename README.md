# Msngr

**The Messenger you remember, in your pocket.**

Msngr brings the feel of MSN Messenger and Windows Live Messenger to Android: a buddy list,
personal messages, display pictures, group conversations, emoticons, and, of course, nudges.
It connects to community Messenger services using MSNP18, with a native Kotlin interface built
with Jetpack Compose and Material 3 Expressive.

Sign in, see who's around, set a personal message, and pick up a conversation. The familiar
Messenger details meet a modern Android layout, with light and dark themes, system appearance,
and dynamic colors on supported devices.

Made by **Aral Özkaya**, with AI assistance. [More on that below.](#made-with-ai-yes-really)

[What it does](#what-it-does) · [Screenshots](#screenshots) · [Compatibility](#services-and-compatibility) · [Getting started](#getting-started) · [Build from source](#build-from-source)

## What it does

- **Keep your people close.** Search contacts and groups, sort by name or presence, hide offline
  entries, and collapse each section independently. Set yourself to Online, Busy, Away, or Appear offline.
- **Chat the classic way.** Send and receive text, use built-in MSN emoticons or modern Unicode
  alternatives, see typing indicators, and send nudges. Conversations have timestamps, unread
  counts, and local history that survives restarts and reconnects.
- **Leave a message.** Send private text and emoticons to offline Escargot contacts and receive
  queued messages when you reconnect, with their original timestamps.
- **Say it out loud.** Hold to record up to 15 seconds with a live waveform,
  drag up to lock hands-free recording, then preview, send, and play
  [voice clips](docs/VOICE_CLIPS.md) in one-to-one chats with online contacts.
  Voice clips do not work in group chats.
- **Bring the group along.** Chat in persistent Messenger groups (MSNP18 circles), see members
  and their roles, create groups, invite people, and join or decline invitations. Owners and
  administrators can invite members; non-owners can leave.
- **Make your profile yours.** Change your picture, display name, and personal message. Messenger
  Plus! formatting supports styled text and both text and background colors in names and personal
  messages, including preset colors, custom colors, and gradients.
  Rendering on other clients depends on their support for Messenger Plus! formatting.
- **Manage your contacts.** Add people by Messenger email address, handle contact requests,
  view profiles, block or unblock contacts, delete contacts, and erase saved conversations.
- **Stay connected on Android.** Receive message, nudge, presence, request, and invitation
  notifications. Reply to messages from notifications. An optional foreground service maintains
  the connection in the background and reconnects after network interruptions.
- **Choose your setup.** Use light, dark, or system appearance, dynamic colors, or
  [classic Messenger scenes](docs/SCENES.md) with matching Material palettes. Choose a main color
  from Messenger swatches or a color wheel. Remember accounts with separate preferences and history for each account
  and service, and choose automatic sign-in.
- **Manage your sessions.** View connected devices and their presence. Sign out an individual
  device, all other sessions, or all sessions. An optional setting signs out other sessions
  whenever Msngr signs in. See [session support](docs/MULTI_PRESENCE.md).
- **Keep Msngr updated.** Signed release builds check GitHub releases on app startup and at a
  configurable interval. Review release notes, opt into prereleases, skip versions, and install
  an update or save its APK. See [app updates](docs/APP_UPDATES.md).

## Screenshots

These are the developer's screenshots from **October 18, 2026**. All 14 screenshots are included
with embedded metadata removed. Select a preview to open the full image.

### Find your people

| Contacts & groups | Quick actions | Sort & filter |
| :---: | :---: | :---: |
| <a href="docs/screenshots/contacts.png"><img src="docs/screenshots/contacts.png" width="240" alt="Main contact list with profile card, search, groups, and contact presence"></a> | <a href="docs/screenshots/contact-actions.png"><img src="docs/screenshots/contact-actions.png" width="240" alt="Expanded plus menu offering Add a contact and Create a group"></a> | <a href="docs/screenshots/sort-and-filter.png"><img src="docs/screenshots/sort-and-filter.png" width="240" alt="Sort sheet with status or name sorting and a switch to hide offline groups and contacts"></a> |

### Make an entrance

| Sign in | Choose a custom service | Edit your profile |
| :---: | :---: | :---: |
| <a href="docs/screenshots/sign-in.png"><img src="docs/screenshots/sign-in.png" width="240" alt="Sign-in form with remember me, automatic sign-in, initial presence, and service selection"></a> | <a href="docs/screenshots/custom-service.png"><img src="docs/screenshots/custom-service.png" width="240" alt="Custom MSNP service dialog with notification host, ports, configuration host, and optional service overrides"></a> | <a href="docs/screenshots/edit-profile.png"><img src="docs/screenshots/edit-profile.png" width="240" alt="Profile editor with a picture, display name, and gradient personal message"></a> |

### Messenger Plus! colors

<a href="docs/screenshots/messenger-plus-colors.png"><img src="docs/screenshots/messenger-plus-colors.png" width="280" alt="Messenger Plus! color sheet with Text color and Background color controls, a Custom color option, preset palette, and Gradient mode below a personal message with a red-to-blue text gradient"></a>

Style display names and personal messages with **text and background colors**, using preset
colors, custom colors, or gradients for either. The screenshot shows the color palette and
formatting controls alongside a personal message with a text gradient.

### Keep the conversation going

| Private messages & nudges | Group messages & sender names | Contact details & controls |
| :---: | :---: | :---: |
| <a href="docs/screenshots/private-chat.png"><img src="docs/screenshots/private-chat.png" width="240" alt="Private chat showing incoming nudges, text messages, and timestamps"></a> | <a href="docs/screenshots/group-chat.png"><img src="docs/screenshots/group-chat.png" width="240" alt="Group conversation with member count, sender attribution, messages, and nudges"></a> | <a href="docs/screenshots/contact-profile.png"><img src="docs/screenshots/contact-profile.png" width="240" alt="Contact profile with picture, offline status, email, block, erase history, and delete actions"></a> |

### Manage your active sessions

<a href="docs/screenshots/active-sessions.png"><img src="docs/screenshots/active-sessions.png" width="280" alt="Active sessions dialog showing Android and desktop device names, online and busy presence, and individual, other-session, and all-session logout options"></a>

Choose exactly where to log out while keeping remembered credentials on the phone. See the
[session support notes](docs/MULTI_PRESENCE.md) for device names, remote logout, and automatic
sign-out of other sessions.

### Catch up with your community

<a href="docs/screenshots/requests-and-invitations.png"><img src="docs/screenshots/requests-and-invitations.png" width="280" alt="Requests and invitations page with separate contact request and group invitation cards"></a>

Respond to contact requests and group invitations from the same page.

<a href="docs/screenshots/android-notifications.png"><img src="docs/screenshots/android-notifications.png" width="720" alt="Android notification shade showing a group invitation, contact request, and Msngr's ongoing connection notification"></a>

Android alerts surface requests and invitations alongside the ongoing background connection.

<a href="docs/screenshots/offline-message-notice.png"><img src="docs/screenshots/offline-message-notice.png" width="720" alt="Private-chat notice explaining that messages to an offline contact will be delivered when they sign in"></a>

An offline private-chat notice explains queued delivery. See the
[delivery limits](docs/OFFLINE_MESSAGES.md#delivery-limits) for the difference between submitting a
message and confirming that it reached its recipient.

## Services and compatibility

Msngr is a **client**: you sign in with an account from your selected community service.

| Service or mode | Current scope |
| --- | --- |
| **Escargot** | Built-in preset. The implementation supports private chats, offline messages, groups, invitations, and session management on Escargot. |
| **CrossTalk** | Built-in endpoint preset. Feature availability depends on the service; the Escargot checks do not establish equivalent CrossTalk coverage. |
| **Custom MSNP service** | Configure notification and switchboard ports, a configuration host, and optional companion-service endpoints. Requires compatible MSNP18 authentication and services. |
| **Mock server** | Debug builds include a local simulation for exploring and testing the interface without a live service account. |

**Android 7.0+ (API 24)** is supported by the app's minimum SDK setting. The project currently
compiles and targets API 37.

This is an evolving implementation of the Messenger experience. Voice/video calls, general file
transfers, and ad hoc multi-person switchboard chats are outside its current scope. Persistent
groups support messaging only while another member is online; offline private messaging is
documented separately. Optional endpoint fields in the custom-service form do not imply that
every associated Messenger feature is implemented.

Two service details are worth knowing: Escargot currently does not deliver custom contact-request
messages to the receiver, although group invitation messages work; and a submitted message is
not a delivery receipt. See the [request-message notes](docs/REQUEST_MESSAGES.md) and
[offline-message limits](docs/OFFLINE_MESSAGES.md#delivery-limits).

## Getting started

1. Install a build of Msngr on an Android device, or [build it from source](#build-from-source).
2. Select **Escargot**, **CrossTalk**, or **Custom** on the sign-in screen. You need an account
   with that service; account registration is handled by the service.
3. Enter your service credentials and choose your initial presence. **Remember me** and
   **Sign me in automatically** are optional.
4. Allow notifications if you want Android alerts. **Keep Msngr Connected** in Settings controls
   the background connection and its ongoing notification.
5. Open a contact or group to chat. Use **+** to add a contact or create a group, and the main
   menu's **Requests & invitations** page to respond to incoming requests.

The **Use mock server** switch shown in the screenshots is available in debug builds.
Background delivery uses a direct Messenger connection. Android notification permissions,
channel settings, and process restrictions affect delivery; force-stopping the app requires
opening it again. See [background behavior and device setup](docs/PUSH_NOTIFICATIONS.md).

## Build from source

Open the repository in Android Studio with support for the pinned Android Gradle Plugin
**9.2.1**, use a compatible Gradle JDK (Android Studio's bundled JDK is the usual starting point),
and install Android SDK Platform **37**. Configure the SDK through Android Studio or your local
`local.properties` file. The repository includes the Gradle **9.4.1** wrapper.
Voice clips also use **NDK 28.2.13676358** and **CMake 3.22.1**; Gradle installs
missing pinned packages when their Android SDK licenses have been accepted.

From PowerShell in the repository root:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
To run the local unit tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

On macOS or Linux, use `./gradlew` in place of `.\gradlew.bat`. Instrumentation tests require
an Android device or emulator. The checked-in tests use synthetic fixtures, mock clients, and
in-memory transports; no community-service credentials are needed.

### Pull request checks

**Build and test Android** runs on every pull request's merged result, pushes to `main`, and
merge queues. It runs the unit tests, builds the debug and release APKs, and compiles the
instrumentation-test APK. Unit-test reports are retained as workflow artifacts for seven days.
Device instrumentation tests still require an emulator or device.

The **Require Android CI** ruleset requires this check from GitHub Actions with the latest
`main` changes before merging. It has no bypass actors, so approval bypass still requires a
successful build. Its importable configuration is checked in at
[`.github/rulesets/require-android-build.json`](.github/rulesets/require-android-build.json).
Keep the job name, this configuration, and the live ruleset's status-check name in sync.

## Downloads

Find debug and release APKs on [GitHub Releases](https://github.com/aralozkaya/Msngr/releases),
along with SHA-256 checksums. Files labelled **unsigned** require signing before installation.

Release tags define the app version: `v1.2.3` becomes **1.2.3** in every APK's metadata and
**Settings → About**. Prereleases use `v1.2.3-alpha.1`, `v1.2.3-beta.1`, or `v1.2.3-rc.1`.
Android version codes are derived from the tag and increase from alpha to beta to release
candidate to stable, then with each patch, minor, or major version. Supported ranges are
major 0–209, minor/patch 0–99, and prerelease number 1–99; malformed tags stop the build.
The GitHub release title is free-form; its tag is the version source.

Local builds use the nearest release tag in the checked-out Git history for both the version
name and Android version code. Fetch new release tags with `git fetch origin --tags` when
updating the checkout. To override the version, pass `-PreleaseTag=v1.2.3` to Gradle or set
`RELEASE_TAG`; the Gradle property takes priority. Builds without a matching Git tag default
to **1.0.0-dev**.

## Under the hood

Kotlin, Jetpack Compose, Material 3 Expressive, StateFlow, WorkManager, and a native MSNP18 client.
The client uses direct TCP connections for Messenger traffic and HTTPS companion services for
authentication, address books, and profile storage. There is no HTTP-tunnel fallback.

Remembered credentials are encrypted on the device with Tink and Android Keystore. Conversation
history is stored separately in private app storage and scoped by account, service, and mock/real
mode. Credential encryption does not provide end-to-end chat encryption.

<details>
<summary>Detailed implementation notes</summary>

### Current implementation

- Each remembered account has its own AES-256-GCM encrypted `StoredAccountDto` for credentials
  and community-server overrides. The encryption keyset is protected by Android Keystore through
  Tink. Accounts are identified by normalized email, notification-server host/port, and mock/real
  mode. Saved accounts are labeled with their service in the login screen's email selector.
- `LoginActivity` validates remembered credentials through the client before opening authenticated
  activities. Failed automatic login clears only the credentials and returns to the form.
- The main screen consumes live `StateFlow` contact data, follows presence changes, sorts by status
  or name, hides offline contacts, edits presence, and routes contact taps to chat. Its profile card
  opens a dedicated editor for the user's picture, display name, and personal message.
- GIF display pictures animate across the app and retain their animation when selected, restored,
  cached, transferred, or downloaded. See [animated-picture compatibility](docs/ANIMATED_PROFILE_PICTURES.md)
  for the native Messenger Soccer packet investigation and decoder details.
- The contacts FAB adds contacts by IM email address, using the native Messenger SOAP and MSNP
  invitation flow with an optional message, validation, loading, failure, and success feedback. Mobile numbers
  and categories are not included. See [`docs/ADDING_CONTACTS.md`](docs/ADDING_CONTACTS.md).
  Debug builds expose **Use mock server** on the login screen for packet-free UI testing, including
  a simulated add delay and a centered Material 3 Expressive loading overlay.
- Contacts can be deleted from the long-press menu or their profile, after confirmation with an
  optional **Also block this contact** checkbox (off by default). Saved chat history is retained,
  and deleted blocked contacts remain in Blocked contacts. The mock server supports the same flow
  without packets. See [`docs/DELETING_CONTACTS.md`](docs/DELETING_CONTACTS.md).
- Chat supports UTF-8 text, the 69 built-in MSN emoticon shortcuts, typing events, MSNP datacast
  nudges, unread counts, and haptic feedback for incoming messages and nudges. Emoticons can use
  either the [original Messenger artwork](app/src/main/MSN_EMOTICONS.md) or modern Unicode glyphs.
  Account- and service-scoped conversation history is saved as paged files in private app storage, loads older
  pages on demand while scrolling, and survives app restarts, reconnections, and contact presence
  changes.
- Private chats support offline text and emoticons on Escargot. Queued messages download at login,
  retain their original timestamps, notify through the Messages channel, and are removed from the
  server only after durable local storage. Retries and reconnects deduplicate server message IDs.
  Offline nudges and typing are disabled. See [`docs/OFFLINE_MESSAGES.md`](docs/OFFLINE_MESSAGES.md).
- Existing accepted MSNP18 groups (circles) appear in a separate Groups section. Group chats
  support text, typing, nudges, sender names, member details, separate saved histories, and Android
  message notifications with inline replies. See [`docs/GROUP_CHATS.md`](docs/GROUP_CHATS.md).
- The expandable FAB creates owner-only groups or groups with initial invitees. Group details lets
  owners and administrators invite members and non-owners leave. Online and offline invitations
  share the **Requests & invitations** page with contact requests, with matching Material 3
  Expressive cards and pull-to-refresh. Group invitations have their own Android notification channel.
  See [`docs/GROUP_CREATION.md`](docs/GROUP_CREATION.md). Custom invitation messages and the current
  Escargot contact-message limitation are documented in [`docs/REQUEST_MESSAGES.md`](docs/REQUEST_MESSAGES.md).
- Android notifications use independent Messages, Nudges, Contact signed in, and Offline messages channels. A standard
  remote-messaging foreground service keeps the direct MSNP connection alive in the background.
  Message notifications support inline replies and interrupted sessions reconnect with capped,
  network-aware backoff.
- Other Messenger sessions for the same account identify this client by Android's configured device
  name (or its model if no name is available). The name is refreshed on each sign-in and reconnect.
  With multiple active sessions, Log out opens a device list with individual, current-device,
  other-session, and all-session logout actions. Remote sign-out returns to login and prevents
  automatic reconnection, background restoration, and queued notification actions from signing
  the client back in. Remembered credentials remain available for explicit sign-in.
  An account-specific Settings toggle, off by default, can sign out every other place
  whenever Msngr signs in.
  See [`docs/MULTI_PRESENCE.md`](docs/MULTI_PRESENCE.md) for live verification.
- Profiles, appearance, emoticon, Messenger Plus!, contact invitation defaults, and background connection settings are stored
  separately for each account and service, including separate mock and real profiles for the same
  email. Switching service or mock mode loads the corresponding saved login options. Turning off
  Remember me immediately removes that account's saved
  credentials, profile, and preferences; turning off automatic sign-in keeps the profile for manual
  login. Unremembered profile settings are cleared on logout. All saved chat histories can be deleted
  together after a confirmation prompt.
- Upgrading from shared or email-only profile storage keeps remembered credentials under their
  recorded service and mode. Ambiguous local profile overrides and UI preferences are reset so
  mock values cannot be restored into a real account; profiles start from the server's values.
  Old email-only conversation files remain on disk, but are not imported into a service's history
  because their original mock/real mode cannot be established.

Background behavior, Android settings, and device verification are documented in
[`docs/PUSH_NOTIFICATIONS.md`](docs/PUSH_NOTIFICATIONS.md).

### MSNP backend boundary

`protocol/Msnp18Protocol.kt` contains MSNP18 negotiation, presence, UUX/UBX personal-message,
typing, text, and nudge support, an incremental wire-frame decoder, and a raw TCP transport. The app
uses direct, proxy-bypassing TCP sockets for both notification-server and switchboard MSNP traffic;
there is no HTTP-tunnel fallback. `MockMsnp18Client` exercises the same contact/message/event boundary
without depending on a community server account. The selected service's HTTPS SSO and SOAP endpoints
are separate companion services and do not carry MSNP wire traffic.

The endpoint presets and protocol behavior were checked against:

- <https://wiki.nina.chat/wiki/Protocols/MSNP/How_to_move>
- <https://git.ugnet.gay/CrossTalk/reroute>
- <https://github.com/pidgin/retro-prpl/tree/main/msn>
- <https://git.ugnet.gay/CrossTalk/msn-pecan>

</details>

| Documentation | What you will find |
| --- | --- |
| [Private offline messages](docs/OFFLINE_MESSAGES.md) | Queue retrieval, timestamps, deduplication, and delivery limits. |
| [Group chats](docs/GROUP_CHATS.md) · [Group creation](docs/GROUP_CREATION.md) | Circles, membership, invitations, roles, and interoperability checks. |
| [Adding contacts](docs/ADDING_CONTACTS.md) · [Deleting contacts](docs/DELETING_CONTACTS.md) | Contact management and retained history. |
| [Contact requests](docs/CONTACT_REQUESTS.md) · [Request messages](docs/REQUEST_MESSAGES.md) | Accept/decline flows, invitation text, and service limitations. |
| [Background notifications](docs/PUSH_NOTIFICATIONS.md) · [Active sessions](docs/MULTI_PRESENCE.md) | Android delivery, reconnects, device names, and remote logout. |
| [Picture synchronization](docs/PROFILE_PICTURE_SYNC.md) | Roaming identity, same-account picture transfers, removals, and desktop interoperability. |

## Made with AI? Yes, really.

AI tools were used to help make Msngr, including during development and writing this documentation.
That's part of how this project was made, and I want to be clear about it.

I'm a software engineer; using tools is kind of the job, lol. AI assistance doesn't change that
the technical decisions, the bugs, and the responsibility for the result are mine.

— Aral Özkaya

## Thanks

- **[Escargot](https://escargot.chat/) and the [NINA community](https://wiki.nina.chat/wiki/Protocols/MSNP/How_to_move)**
  for keeping Messenger usable and preserving the service and protocol knowledge that makes projects like this possible.
- **[CrossTalk](https://crosstalk.im/)** for its community service and the
  [reroute](https://git.ugnet.gay/CrossTalk/reroute) and
  [msn-pecan](https://git.ugnet.gay/CrossTalk/msn-pecan) references used by this project.
- **[Pidgin / retro-prpl](https://github.com/pidgin/retro-prpl)** for preserving implementations
  that help explain how these older protocols work.
- **[bernzrdo/msn-emoticons](https://github.com/bernzrdo/msn-emoticons)** for the preserved
  Messenger emoticon collection. See the repository's [artwork provenance notes](app/src/main/MSN_EMOTICONS.md).
- **The Kotlin, Android, Jetpack Compose, Material, and Tink maintainers** for the tools behind the app.
- **Everyone testing Msngr, sharing feedback, and still sending nudges** for giving it people to connect.

Msngr is an independent community project, unaffiliated with Microsoft, Escargot, NINA, or
CrossTalk. MSN Messenger, Windows Live Messenger, and Messenger Plus! names and artwork belong
to their respective owners.
