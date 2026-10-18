package org.aralozkaya.msngr

import android.content.Context
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.enums.Presence

internal suspend fun Context.restoreSignedInProfile(
    repository: MessengerRepository,
    serverProfile: UserProfileSnapshot,
    presence: Presence,
) {
    val mergedProfile = SessionPrefs.mergeServerProfile(this, serverProfile)
        .copy(presence = presence)
    val localPictureBytes = loadDisplayPictureBytes(mergedProfile.avatarUri)
    val serverPictureBytes = if (
        mergedProfile.avatarUri != null &&
        localPictureBytes == null &&
        mergedProfile.avatarUri != serverProfile.avatarUri
    ) {
        loadDisplayPictureBytes(serverProfile.avatarUri)
    } else {
        null
    }
    val localProfile = if (serverPictureBytes != null) {
        mergedProfile.copy(avatarUri = serverProfile.avatarUri)
    } else {
        mergedProfile
    }
    SessionPrefs.saveUserProfile(
        context = this,
        username = localProfile.username,
        presence = presence,
        avatarUri = localProfile.avatarUri,
        statusMessage = localProfile.statusMessage,
    )
    repository.setLocalProfile(
        profile = localProfile,
        publishStoredSettings = true,
        displayPictureBytes = localPictureBytes ?: serverPictureBytes,
    )
}
