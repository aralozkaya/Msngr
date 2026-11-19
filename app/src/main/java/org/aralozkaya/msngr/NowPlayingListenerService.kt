package org.aralozkaya.msngr

import android.service.notification.NotificationListenerService

/**
 * Enables access to active [android.media.session.MediaSessionManager] playback sessions
 * when authorized by the user in Android's Notification access settings.
 *
 * Notifications themselves are never read and are filtered out via manifest metadata.
 */
class NowPlayingListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        (applicationContext as? MsngrApplication)?.nowPlayingController?.onNotificationAccessChanged()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        (applicationContext as? MsngrApplication)?.nowPlayingController?.onNotificationAccessChanged()
    }
}
