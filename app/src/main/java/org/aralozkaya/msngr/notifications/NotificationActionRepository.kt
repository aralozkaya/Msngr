package org.aralozkaya.msngr.notifications

import android.content.Context
import org.aralozkaya.msngr.MsngrApplication
import org.aralozkaya.msngr.SessionPrefs
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.SignInRequest

/** Returns an active repository, reconnecting or restoring the remembered session if necessary. */
internal suspend fun Context.connectedRepositoryForNotificationAction(): MessengerRepository? {
    val appContext = applicationContext
    if (SessionPrefs.wasRemotelySignedOut(appContext)) return null
    val app = appContext as MsngrApplication
    val existing = runCatching { app.container.messengerRepository }.getOrNull()
    if (
        existing != null &&
        existing.hasSignedInSession &&
        existing.connectionIssue.value == null
    ) {
        return existing
    }
    if (existing?.hasSignedInSession == true && existing.reconnect().isSuccess) return existing

    val snapshot = SessionPrefs.readLoginSnapshot(appContext)
    if (!snapshot.savePassword || snapshot.email.isBlank() || snapshot.password.isEmpty()) {
        return null
    }
    val repository = app.container.repositoryFor(snapshot.useMockServer)
    val signedIn = repository.signIn(
        SignInRequest(
            email = snapshot.email,
            password = snapshot.password,
            serverConfig = snapshot.resolvedServers,
            initialPresence = snapshot.loginPresence,
        ),
    )
    if (signedIn.isFailure) return null
    SessionPrefs.activateRememberedSession(appContext)
    repository.setLocalProfile(
        SessionPrefs.mergeServerProfile(appContext, signedIn.getOrThrow()),
    )
    return repository
}
