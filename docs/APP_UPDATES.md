# App updates

Open **Settings → App updates** to check GitHub releases and review the newest compatible
version tag, publication date, and release notes. Updates work only in installed, signed
`release` builds. Debug builds show an explanation and keep updater actions disabled.

## Checking and notifications

- Automatic checking defaults to every **24 hours**, plus each fresh app launch. Choose
  6 hours, 12 hours, 24 hours, 2 days, a week, or app launches only.
- Android WorkManager schedules background checks with a network constraint. Android can
  delay these jobs for battery or network reasons; the interval is not an exact alarm.
- A new activity session triggers a startup check. Navigation, configuration changes,
  connection restoration, and background service/worker startup do not trigger startup checks.
- Turn off **Check automatically** to cancel automatic checking. **Check for updates**
  continues to work manually. The checking frequency selector expands below the switch
  while automatic checking is enabled; collapsing it preserves the selected frequency.
- Stable releases are the default. **Include prereleases** also offers alpha, beta, and rc
  releases. Versions follow the release-tag/versionCode contract in `app/build.gradle.kts`;
  republishing an old version does not make it an upgrade.
- **Skip this version** suppresses that release's badges and notification. A later version
  is still offered. The release remains available on the updates page for a manual download;
  **Stop skipping this version** restores its badge.
- A separate **App updates** Android notification channel announces each newly discovered
  version once. Notification permission and channel preferences apply. The overflow menu,
  its Settings item, the App updates settings row, and the update page show badges even
  when notifications are unavailable. Notification taps open the updater without requiring
  a Messenger connection or sign-in.

Updater preferences are app-wide, independent of accounts. Release metadata, last successful
check time, skipped version, and notification deduplication survive process restarts. A failed
check reports an error and preserves previously discovered release information.

## Downloading and installing

**Download APK** offers **Download and install** or **Save APK to a folder**. The existing
release workflow publishes `Msngr-release.apk`, `Msngr-release-unsigned.apk`, and
`Msngr-debug.apk`; the updater downloads only `Msngr-release.apk`. If the release is published
before its APK finishes building, release details remain visible and explain that the signed
APK is not attached yet.

WorkManager downloads the APK into the app's private `cache/updates/` directory. Downloads
show progress, support cancellation, and can continue while the screen is closed. A completed
download can be installed or saved when the page is reopened. Interrupted downloads restart
from the beginning. Partial files are never offered for installation.

Before installation or export, the app checks asset size, GitHub's SHA-256 digest when supplied,
package identity, exact release versionCode, minimum Android version, and the installed app's
signing certificate. Download redirects must remain on HTTPS GitHub/GitHubusercontent hosts.
The current implementation requires the same signing certificate; signing-key rotation is
not supported by the updater.

Installation uses a FileProvider URI restricted to the update cache and opens Android's APK
installer. If necessary, Android first asks the user to allow installations from Msngr. The
user confirms the installation in Android. A package-replaced receiver, with startup cleanup
as a fallback, deletes cached APKs for installed versions after a successful update. Abandoned
temporary files expire after seven days. APKs explicitly saved through Android's document
picker remain in the user's chosen location.

## Validation

`UpdateModelsTest` covers version ordering, unsupported tags, stable/prerelease selection,
skip badges, genuine launches, reconnections, and configuration changes.
`UpdateInstrumentedTest` covers release JSON, signed asset selection, persisted preferences,
manual checks while disabled, channel switching, network errors, build eligibility, and URL
restrictions. `UpdateScreenInstrumentedTest` exercises release controls at 200% font size
and the disabled debug-build screen.

For a complete installation smoke test, install an older signed release on a device, publish
a higher tagged release signed with the same key, then exercise both installer confirmation
and document-picker export. Confirm that the cached APK disappears after successful package
replacement. This requires two real versions and the release signing key.
