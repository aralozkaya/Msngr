package org.aralozkaya.msngr

import android.content.Context
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.enums.Presence

internal suspend fun Context.restoreSignedInProfile(
    repository: MessengerRepository,
    serverProfile: UserProfileSnapshot,
    presence: Presence,
) {
    val roamingEnabled = DisplayPictureSyncPrefs.usesSamePictureAndStatusEverywhere(this)
    val merged = SessionPrefs.mergeSignedInProfile(this, serverProfile)
    val mergedProfile = merged.profile.copy(presence = presence)
    repository.setLocalProfile(
        profile = mergedProfile,
        publishStoredSettings = true,
        // Fetch and compare before publishing a local picture over the server's one.
        publishDisplayPicture = false,
        publishStatusMessage = false,
    )
    if (merged.shouldPublishStatusMessage) {
        repository.updateStatusMessage(mergedProfile.statusMessage).onSuccess {
            if (roamingEnabled) {
                SessionPrefs.recordServerStatusMessage(this, mergedProfile.statusMessage)
                SessionPrefs.setPendingStatusMessagePublish(this, false)
            }
        }
    } else if (roamingEnabled) {
        SessionPrefs.recordServerStatusMessage(this, serverProfile.statusMessage)
        SessionPrefs.setPendingStatusMessagePublish(this, false)
    }
    displayPictureSync.restore(repository, serverProfile,
        skipNextRefresh = (applicationContext as MsngrApplication).visibilityTracker.isForeground)
    val localProfile = repository.profile.value
    SessionPrefs.saveUserProfile(
        context = this,
        username = localProfile.username,
        presence = presence,
        avatarUri = localProfile.avatarUri,
        statusMessage = localProfile.statusMessage,
        saveDisplayPicture = false,
    )
}
