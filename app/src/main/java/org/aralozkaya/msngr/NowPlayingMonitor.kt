package org.aralozkaya.msngr

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.aralozkaya.msngr.protocol.CurrentMedia
import org.aralozkaya.msngr.protocol.MsnCurrentMedia

internal enum class MediaPlaybackCategory {
    Playing,
    Transitioning,
    Inactive,
}

internal data class MediaSessionSnapshot(
    val packageName: String,
    val playbackCategory: MediaPlaybackCategory,
    val title: String?,
    val artist: String?,
    val album: String?,
)

/** Pure selection rules for picking the active track across system media sessions. */
internal object NowPlayingMediaSelector {
    fun selectCurrentMedia(
        sessions: List<MediaSessionSnapshot>,
        ownPackageName: String,
    ): CurrentMedia? {
        val eligible = sessions.filterNot { it.packageName.equals(ownPackageName, ignoreCase = true) }
        val playing = eligible
            .asSequence()
            .filter { it.playbackCategory == MediaPlaybackCategory.Playing }
            .mapNotNull(::toCurrentMedia)
            .firstOrNull()
        if (playing != null) return playing
        return eligible
            .asSequence()
            .filter { it.playbackCategory == MediaPlaybackCategory.Transitioning }
            .mapNotNull(::toCurrentMedia)
            .firstOrNull()
    }

    fun classifyPlaybackState(state: Int?): MediaPlaybackCategory = when (state) {
        PlaybackState.STATE_PLAYING -> MediaPlaybackCategory.Playing
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> MediaPlaybackCategory.Transitioning
        else -> MediaPlaybackCategory.Inactive
    }

    private fun toCurrentMedia(snapshot: MediaSessionSnapshot): CurrentMedia? {
        val title = snapshot.title?.trim().orEmpty()
        if (title.isEmpty()) return null
        return MsnCurrentMedia.music(
            title = title,
            artist = snapshot.artist.orEmpty(),
            album = snapshot.album.orEmpty(),
        )
    }
}

/** Checks Notification access and creates system settings intents for enabling [NowPlayingListenerService]. */
internal object NowPlayingAccess {
    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"

    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, NowPlayingListenerService::class.java)

    fun isNotificationAccessGranted(context: Context): Boolean {
        val component = listenerComponent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager?.isNotificationListenerAccessGranted(component) == true
        }
        val flat = Settings.Secure.getString(context.contentResolver, ENABLED_NOTIFICATION_LISTENERS)
            ?: return false
        return flat.split(':').any { entry ->
            val candidate = ComponentName.unflattenFromString(entry)
            candidate == component
        }
    }

    fun createNotificationAccessIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detailIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                listenerComponent(context).flattenToString(),
            )
            if (detailIntent.resolveActivity(context.packageManager) != null) {
                return detailIntent
            }
        }
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    fun createAppDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
}

internal interface NowPlayingSessionSource {
    fun start(
        onMediaChanged: (CurrentMedia?) -> Unit,
        onAccessLost: () -> Unit,
    ): Boolean

    fun stop()
}

/**
 * Observes active system [MediaController] instances via [MediaSessionManager].
 * Uses [NowPlayingListenerService] strictly as the authorization component for media session access.
 */
internal class AndroidNowPlayingSessionSource(
    private val context: Context,
) : NowPlayingSessionSource {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var currentOnMediaChanged: ((CurrentMedia?) -> Unit)? = null
    private var currentOnAccessLost: (() -> Unit)? = null
    private val callbacksByToken =
        LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var orderedTokens: List<MediaSession.Token> = emptyList()

    override fun start(
        onMediaChanged: (CurrentMedia?) -> Unit,
        onAccessLost: () -> Unit,
    ): Boolean {
        stop()
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return false
        val component = NowPlayingAccess.listenerComponent(context)
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            syncControllers(controllers)
        }
        synchronized(lock) {
            mediaSessionManager = manager
            activeSessionsListener = listener
            currentOnMediaChanged = onMediaChanged
            currentOnAccessLost = onAccessLost
        }
        return try {
            manager.addOnActiveSessionsChangedListener(listener, component, mainHandler)
            syncControllers(manager.getActiveSessions(component))
            true
        } catch (_: SecurityException) {
            stop()
            onAccessLost()
            false
        }
    }

    override fun stop() {
        val manager: MediaSessionManager?
        val listener: MediaSessionManager.OnActiveSessionsChangedListener?
        val registered: List<Pair<MediaController, MediaController.Callback>>
        synchronized(lock) {
            manager = mediaSessionManager
            listener = activeSessionsListener
            mediaSessionManager = null
            activeSessionsListener = null
            currentOnMediaChanged = null
            currentOnAccessLost = null
            registered = callbacksByToken.values.toList()
            callbacksByToken.clear()
            orderedTokens = emptyList()
        }
        if (manager != null && listener != null) {
            runCatching { manager.removeOnActiveSessionsChangedListener(listener) }
        }
        registered.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
    }

    private fun syncControllers(controllers: List<MediaController>?) {
        val list = controllers.orEmpty()
        val stale = mutableListOf<Pair<MediaController, MediaController.Callback>>()
        val toRegister = mutableListOf<Pair<MediaController, MediaController.Callback>>()
        synchronized(lock) {
            if (activeSessionsListener == null) return
            val incomingByToken = LinkedHashMap<MediaSession.Token, MediaController>()
            list.forEach { controller ->
                incomingByToken.putIfAbsent(controller.sessionToken, controller)
            }
            val currentTokens = incomingByToken.keys
            val iterator = callbacksByToken.entries.iterator()
            while (iterator.hasNext()) {
                val (token, pair) = iterator.next()
                if (token !in currentTokens) {
                    stale += pair
                    iterator.remove()
                }
            }
            incomingByToken.forEach { (token, controller) ->
                if (token !in callbacksByToken) {
                    val callback = controllerCallback(token)
                    callbacksByToken[token] = controller to callback
                    toRegister += controller to callback
                }
            }
            orderedTokens = currentTokens.toList()
        }
        stale.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        toRegister.forEach { (controller, callback) ->
            runCatching { controller.registerCallback(callback, mainHandler) }
        }
        emitCurrentSelection()
    }

    private fun controllerCallback(token: MediaSession.Token): MediaController.Callback =
        object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                emitCurrentSelection()
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                emitCurrentSelection()
            }

            override fun onSessionDestroyed() {
                val removed = synchronized(lock) {
                    orderedTokens = orderedTokens.filterNot { it == token }
                    callbacksByToken.remove(token)
                }
                if (removed != null) {
                    runCatching { removed.first.unregisterCallback(removed.second) }
                    emitCurrentSelection()
                }
            }
        }

    private fun emitCurrentSelection() {
        val onChanged: ((CurrentMedia?) -> Unit)?
        val snapshots: List<MediaSessionSnapshot>
        synchronized(lock) {
            onChanged = currentOnMediaChanged ?: return
            snapshots = orderedTokens.mapNotNull { token ->
                val controller = callbacksByToken[token]?.first ?: return@mapNotNull null
                snapshotOf(controller)
            }
        }
        val selected = NowPlayingMediaSelector.selectCurrentMedia(snapshots, context.packageName)
        onChanged?.invoke(selected)
    }

    private fun snapshotOf(controller: MediaController): MediaSessionSnapshot? = runCatching {
        val metadata = controller.metadata
        val title = metadata?.firstNonBlank(
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
        )
        val artist = metadata?.firstNonBlank(
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_AUTHOR,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        )
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        MediaSessionSnapshot(
            packageName = controller.packageName.orEmpty(),
            playbackCategory = NowPlayingMediaSelector.classifyPlaybackState(controller.playbackState?.state),
            title = title,
            artist = artist,
            album = album,
        )
    }.getOrNull()

    private fun MediaMetadata.firstNonBlank(vararg keys: String): String? {
        for (key in keys) {
            val value = getString(key)?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }
}
