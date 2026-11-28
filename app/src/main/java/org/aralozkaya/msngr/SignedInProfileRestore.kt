package org.aralozkaya.msngr

import android.content.Context
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.SessionUnavailableException
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence

internal suspend fun Context.restoreRememberedRepository(snapshot: LoginSnapshot): Result<MessengerRepository> {
    val account = snapshot.accountIdentity
    if (!snapshot.savePassword || snapshot.password.isEmpty() || SessionPrefs.preferenceAccount(this) != account ||
        SessionPrefs.wasRemotelySignedOut(this, account)) {
        return Result.failure(SessionUnavailableException("The remembered session is no longer available."))
    }
    val repository = appContainer.repositoryForRestore(snapshot.useMockServer)
        ?: return Result.failure(SessionUnavailableException("The selected service has changed."))
    val request = SignInRequest(snapshot.email, snapshot.password, snapshot.resolvedServers, snapshot.loginPresence)
    return repository.signIn(request) {
        SessionPrefs.activateRememberedSession(this, account, snapshot.password)
    }.map { profile ->
        restoreSignedInProfile(repository, profile, profile.presence, account)
        repository
    }
}

internal suspend fun Context.restoreSignedInProfile(
    repository: MessengerRepository,
    serverProfile: UserProfileSnapshot,
    presence: Presence,
    account: AccountIdentity,
) {
    if (repository.signedInAccountIdentity != account || SessionPrefs.activeAccount() != account) return
    val roamingEnabled = DisplayPictureSyncPrefs.usesSamePictureAndStatusEverywhere(this, account)
    val merged = SessionPrefs.mergeSignedInProfile(this, serverProfile, account)
    val livePresence = if (
        repository.hasSignedInSession &&
        repository.profile.value.email.equals(serverProfile.email, ignoreCase = true)
    ) {
        repository.profile.value.presence
    } else {
        presence
    }
    val mergedProfile = merged.profile.copy(presence = livePresence)
    repository.setLocalProfile(
        profile = mergedProfile,
        publishStoredSettings = true,
        // Fetch and compare before publishing a local picture over the server's one.
        publishDisplayPicture = false,
        publishStatusMessage = false,
        account = account,
    )
    if (merged.shouldPublishStatusMessage) {
        repository.updateStatusMessage(mergedProfile.statusMessage, account).onSuccess {
            if (roamingEnabled) {
                SessionPrefs.recordServerStatusMessage(this, mergedProfile.statusMessage, account)
                SessionPrefs.setPendingStatusMessagePublish(this, false, account)
            }
        }
    } else if (roamingEnabled) {
        SessionPrefs.recordServerStatusMessage(this, serverProfile.statusMessage, account)
        SessionPrefs.setPendingStatusMessagePublish(this, false, account)
    }
    displayPictureSync.restore(repository, serverProfile, expected = account,
        skipNextRefresh = (applicationContext as MsngrApplication).visibilityTracker.isForeground)
    val localProfile = repository.profile.value
    if (repository.signedInAccountIdentity != account || SessionPrefs.activeAccount() != account ||
        !localProfile.email.equals(account.email, ignoreCase = true)) return
    SessionPrefs.saveUserProfile(
        context = this,
        username = localProfile.username,
        presence = localProfile.presence,
        avatarUri = localProfile.avatarUri,
        statusMessage = localProfile.statusMessage,
        saveDisplayPicture = false,
        account = account,
    )
}
