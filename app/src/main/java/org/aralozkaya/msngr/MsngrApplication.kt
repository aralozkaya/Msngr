package org.aralozkaya.msngr

import android.app.Application
import android.content.Context
import org.aralozkaya.msngr.data.ConversationDraftStore
import org.aralozkaya.msngr.data.persistentConversationDraftStore
import org.aralozkaya.msngr.data.ConversationStyleStore
import org.aralozkaya.msngr.data.persistentConversationStyleStore
import org.aralozkaya.msngr.data.ConversationHistoryStore
import org.aralozkaya.msngr.data.FileContactDisplayPictureCache
import org.aralozkaya.msngr.data.FileConversationHistoryStore
import org.aralozkaya.msngr.data.InMemoryConversationHistoryStore
import org.aralozkaya.msngr.data.LiveMsnp18Client
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.MsnpClient
import org.aralozkaya.msngr.notifications.AppVisibilityTracker
import org.aralozkaya.msngr.notifications.BackgroundConnectionController
import org.aralozkaya.msngr.notifications.MsngrNotificationManager
import org.aralozkaya.msngr.notifications.NotificationChannels
import org.aralozkaya.msngr.notifications.RealtimeNotificationBridge
import java.io.File

class MsngrApplication : Application() {
    internal val displayPictureSync by lazy { DisplayPictureSync(this) }
    val updateManager by lazy { org.aralozkaya.msngr.updates.UpdateManager(this) }
    internal val visibilityTracker = AppVisibilityTracker { isForeground ->
        runCatching { container.messengerRepository }.getOrNull()?.let { repository ->
            repository.setAppInForeground(isForeground)
            displayPictureSync.setForeground(isForeground)
            if (isForeground && SessionPrefs.canOpenAuthenticatedScreens(this)) displayPictureSync.requestRefresh(repository)
        }
    }
    internal val notificationManager by lazy { MsngrNotificationManager(this) }
    private val realtimeNotificationBridge by lazy {
        RealtimeNotificationBridge(visibilityTracker, notificationManager)
    }
    private val networkMonitor by lazy { NetworkMonitor(this) }

    val container: AppContainer by lazy {
        AppContainer(
            clientFactory = { useMockServer ->
                if (useMockServer) {
                    MockMsnp18Client()
                } else {
                    LiveMsnp18Client(
                        endpointName = { deviceEndpointName(this) },
                        contactDisplayPictureCache = FileContactDisplayPictureCache(
                            File(filesDir, "contact_display_pictures"),
                        ),
                    )
                }
            },
            onRepositoryCreated = { repository ->
                realtimeNotificationBridge.attach(repository)
                displayPictureSync.attach(repository)
                displayPictureSync.setForeground(visibilityTracker.isForeground)
                repository.setAppInForeground(visibilityTracker.isForeground)
            },
            awaitNetworkAccess = networkMonitor::awaitInternetAccess,
            conversationHistoryStore = FileConversationHistoryStore(
                directory = File(noBackupFilesDir, "conversation_history"),
                legacyFile = File(noBackupFilesDir, "conversation_history.bin"),
            ),
            messengerPlusEnabled = { MessengerPlusPrefs.isEnabled(this) },
            conversationDraftStore = persistentConversationDraftStore(this),
            conversationStyleStore = persistentConversationStyleStore(this),
            onConversationRead = notificationManager::dismissConversation,
            onRemoteSignOut = {
                // A remote sign-out must survive process restart and background service restore.
                SessionPrefs.recordRemoteSignOut(this)
                SessionPrefs.logout()
                BackgroundConnectionController.stop(this)
                AppShortcuts.clearRecentChats(this)
            },
            signOutOtherSessionsOnSignIn = { account ->
                SessionPrefs.signsOutOtherSessionsOnSignIn(this, account)
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(visibilityTracker)
        NotificationChannels.createAll(this)
        updateManager.initialize(this)
        AppShortcuts.refresh(this)
    }
}

class AppContainer(
    private val clientFactory: (useMockServer: Boolean) -> MsnpClient = { useMockServer ->
        if (useMockServer) MockMsnp18Client() else LiveMsnp18Client()
    },
    private val onRepositoryCreated: (MessengerRepository) -> Unit = {},
    private val awaitNetworkAccess: suspend () -> Unit = {},
    private val conversationHistoryStore: ConversationHistoryStore =
        InMemoryConversationHistoryStore(),
    private val messengerPlusEnabled: () -> Boolean = { true },
    private val conversationDraftStore: ConversationDraftStore = ConversationDraftStore(),
    private val onConversationRead: (String) -> Unit = {},
    private val onRemoteSignOut: () -> Unit = {},
    private val signOutOtherSessionsOnSignIn: (AccountIdentity) -> Boolean = { false },
    private val conversationStyleStore: ConversationStyleStore = ConversationStyleStore(),
) {
    private var repository: MessengerRepository? = null
    private var repositoryUsesMockServer: Boolean? = null

    @Synchronized
    fun repositoryFor(useMockServer: Boolean): MessengerRepository {
        val current = repository
        if (current != null && repositoryUsesMockServer == useMockServer) return current

        current?.logout()
        return MessengerRepository(
            client = clientFactory(useMockServer),
            awaitNetworkAccess = awaitNetworkAccess,
            historyStore = conversationHistoryStore,
            draftStore = conversationDraftStore,
            styleStore = conversationStyleStore,
            messengerPlusEnabled = messengerPlusEnabled,
            onConversationRead = onConversationRead,
            useMockServer = useMockServer,
            onRemoteSignOut = onRemoteSignOut,
            signOutOtherSessionsOnSignIn = signOutOtherSessionsOnSignIn,
        ).also {
            repository = it
            repositoryUsesMockServer = useMockServer
            onRepositoryCreated(it)
        }
    }

    val messengerRepository: MessengerRepository
        @Synchronized get() = checkNotNull(repository) {
            "The MSNP backend must be selected by a successful login first."
        }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MsngrApplication).container
