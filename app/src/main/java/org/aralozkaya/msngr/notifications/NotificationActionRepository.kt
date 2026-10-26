package org.aralozkaya.msngr.notifications

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.aralozkaya.msngr.MsngrApplication
import org.aralozkaya.msngr.SessionPrefs
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.restoreSignedInProfile

/** A connection attempt failed before a notification action could be delivered. */
internal class NotificationActionSessionRestoreException(cause: Throwable) :
    Exception("Could not restore the notification action session", cause)

/**
 * Returns an active repository, reconnecting or restoring the remembered session if necessary.
 * A null result requires sign-in; a failed connection attempt throws so workers can retry it.
 */
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
    var reconnectFailure: Throwable? = null
    if (existing?.hasSignedInSession == true) {
        val reconnect = existing.reconnect()
        reconnectFailure = reconnect.exceptionOrNull()
        if (reconnectFailure is CancellationException) throw reconnectFailure
        val refreshed = reconnect.getOrNull()
        if (refreshed != null) {
            appContext.restoreSignedInProfile(existing, refreshed, refreshed.presence)
            return existing
        }
    }

    val snapshot = SessionPrefs.readLoginSnapshot(appContext)
    if (!snapshot.savePassword || snapshot.email.isBlank() || snapshot.password.isEmpty()) {
        // The in-memory session can still reconnect even when its credentials were not saved.
        reconnectFailure?.let { throw NotificationActionSessionRestoreException(it) }
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
    signedIn.exceptionOrNull()?.let { failure ->
        if (failure is CancellationException) throw failure
        throw NotificationActionSessionRestoreException(failure)
    }
    SessionPrefs.activateRememberedSession(appContext)
    val profile = signedIn.getOrThrow()
    appContext.restoreSignedInProfile(repository, profile, profile.presence)
    return repository
}
