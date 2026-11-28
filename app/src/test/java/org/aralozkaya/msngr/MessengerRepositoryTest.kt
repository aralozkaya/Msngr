@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.aralozkaya.msngr

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.data.ConversationHistoryStore
import org.aralozkaya.msngr.data.InMemoryConversationHistoryStore
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupMember
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.data.MessengerEvent
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.MockMsnp18Client
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.data.MsnpClient
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

class MessengerRepositoryTest {
    @Test fun savedNativeStyleAppliesToEverySendAndSurvivesDraftClearingAndLogout() = runBlocking {
        val client = MockMsnp18Client(signInDelayMillis = 0)
        val repository = MessengerRepository(client, messengerPlusEnabled = { false })
        val request = SignInRequest(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD,
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))
        val selected = NativeTextStyle(true, true, true, true, 0x123456, 22)
        try {
            repository.signIn(request).getOrThrow()
            repository.updateTextStyle("selin", selected)
            repository.updateTextStyle("group@live.com", selected.copy(size = 18))
            repository.updateDraft("selin", "[b]draft[/b]")
            repository.updateDraft("selin", "")
            repository.sendMessageNow("selin", "[b]first[/b]").getOrThrow()
            repository.sendMessage("selin", "second")
            withTimeout(2_000) { client.messages.first { it["selin"].orEmpty().count { message -> message.author == MessageAuthor.Me } == 2 } }
            assertTrue(client.messages.value.getValue("selin").filter { it.author == MessageAuthor.Me }
                .all { it.nativeStyle == selected })
            assertEquals("first", client.messages.value.getValue("selin").first().text)
            repository.sendMessageNow("mert", "default").getOrThrow()
            assertEquals(NativeTextStyle(), client.messages.value.getValue("mert").first().nativeStyle)
            repository.sendMessageNow("group@live.com", "group").getOrThrow()
            assertEquals(selected.copy(size = 18), client.messages.value.getValue("group@live.com").first().nativeStyle)
            repository.logout()
            assertTrue(repository.textStyles.value.isEmpty())
            repository.signIn(request).getOrThrow()
            assertEquals(selected, repository.textStyles.value["selin"])
            repository.sendMessageNow("selin", "after login").getOrThrow()
            assertEquals(selected, client.messages.value.getValue("selin").first().nativeStyle)
        } finally { repository.logout() }
    }
    private val currentSession = org.aralozkaya.msngr.data.AccountSession("current", "Phone", true)
    private val remoteSessions = listOf(
        org.aralozkaya.msngr.data.AccountSession("laptop", "Laptop"),
        org.aralozkaya.msngr.data.AccountSession("desktop", "Desktop"),
    )

    @Test fun signInKeepsOtherSessionsByDefault() = runBlocking {
        val client = RecordingMsnpClient().apply { sessions.value = listOf(currentSession) + remoteSessions }
        val repository = MessengerRepository(client)
        try {
            repository.signIn(SignInRequest(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD,
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            assertTrue(client.loggedOutSessions.isEmpty())
            assertEquals(3, repository.sessions.value.size)
        } finally { repository.logout() }
    }

    @Test fun signInAndReconnectUseTheCurrentAccountsSessionSetting() = runBlocking {
        val client = RecordingMsnpClient().apply { sessions.value = listOf(currentSession) + remoteSessions }
        val request = SignInRequest(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD,
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))
        val expectedAccount = AccountIdentity.forServer(request.email, request.serverConfig, false)
        var enabled = true
        val repository = MessengerRepository(client, signOutOtherSessionsOnSignIn = { account ->
            assertEquals(expectedAccount, account)
            enabled
        })
        try {
            repository.signIn(request).getOrThrow()
            assertEquals(listOf("laptop", "desktop"), client.loggedOutSessions)
            assertEquals(listOf(currentSession), repository.sessions.value)
            assertTrue(repository.hasSignedInSession)
            client.sessions.value = listOf(currentSession) + remoteSessions
            enabled = false
            repository.reconnect().getOrThrow()
            assertEquals(2, client.loggedOutSessions.size)
            enabled = true
            repository.reconnect().getOrThrow()
            assertEquals(listOf("laptop", "desktop", "laptop", "desktop"), client.loggedOutSessions)
            assertTrue(repository.hasSignedInSession)
        } finally { repository.logout() }
    }

    @Test fun failedAutomaticSessionLogoutDoesNotFinishSignIn() = runBlocking {
        val client = RecordingMsnpClient().apply {
            sessions.value = listOf(currentSession) + remoteSessions
            logoutFailure = IOException("Rejected")
        }
        val repository = MessengerRepository(client, signOutOtherSessionsOnSignIn = { true })
        try {
            val result = repository.signIn(SignInRequest(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD,
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides())))
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("all other places"))
            assertTrue(!repository.hasSignedInSession)
            assertEquals(listOf("close"), client.sessionActions)
        } finally { repository.logout() }
    }

    @Test fun logoutScopesSelectOnlyRequestedSessionsAndCurrentEndsLast() = runBlocking {
        for (ids in listOf(setOf("laptop"), setOf("current"), setOf("laptop", "desktop"),
            setOf("current", "laptop", "desktop"))) {
            val client = RecordingMsnpClient()
            val repository = MessengerRepository(client)
            repository.signIn(SignInRequest(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD,
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            client.sessions.value = listOf(currentSession) + remoteSessions
            repository.logOutSessions(ids).getOrThrow()
            assertEquals(remoteSessions.filter { it.id in ids }.map { it.id }, client.loggedOutSessions)
            assertEquals("current" !in ids, repository.hasSignedInSession)
            if ("current" in ids) assertEquals("close", client.sessionActions.last())
            repository.logout()
        }
    }

    @Test fun failedRemoteLogoutLeavesCurrentSessionAvailable() = runBlocking {
        val client = RecordingMsnpClient().apply { logoutFailure = IOException("Rejected") }
        val repository = MessengerRepository(client)
        try {
            repository.signIn(SignInRequest(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD,
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            client.sessions.value = listOf(currentSession) + remoteSessions
            assertTrue(repository.logOutSessions(setOf("current", "laptop", "desktop")).isFailure)
            assertTrue(repository.hasSignedInSession)
            assertTrue(client.sessionActions.isEmpty())
        } finally { repository.logout() }
    }

    @Test fun remoteSignoutStopsReconnectAndRunsDurableCleanup() = runBlocking {
        val client = RecordingMsnpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var cleanupCount = 0
        val repository = MessengerRepository(client, scope = scope, onRemoteSignOut = { cleanupCount++ })
        try {
            repository.signIn(SignInRequest(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD,
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            repository.setPersistentBackgroundConnection(true)
            client.events.emit(MessengerEvent.ConnectionLost("Broken pipe"))
            client.events.emit(MessengerEvent.SignedOut("Logged out remotely"))
            withTimeout(1_000) { repository.signOutReason.first { it != null } }
            assertEquals(1, cleanupCount)
            assertTrue(!repository.hasSignedInSession)
            assertNull(repository.connectionIssue.value)
            repository.setAppInForeground(true)
            repository.setPersistentBackgroundConnection(true)
            assertTrue(repository.reconnect().isFailure)
            kotlinx.coroutines.delay(1_100)
            assertEquals(1, client.signInRequests.size)
        } finally { repository.logout(); scope.cancel() }
    }

    @Test
    fun lateMessagesFromALoggedOutMockSessionCannotEnterTheRealAccountsHistory() = runBlocking {
        val history = org.aralozkaya.msngr.data.InMemoryConversationHistoryStore()
        val retiredClient = RecordingMsnpClient()
        val mockScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val mock = MessengerRepository(retiredClient, scope = mockScope, historyStore = history, useMockServer = true)
        val real = MessengerRepository(RecordingMsnpClient(), scope = realScope, historyStore = history, useMockServer = false)
        val request = SignInRequest("same@example.com", "password",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))
        val friend = "friend@example.com"
        try {
            mock.signIn(request).getOrThrow()
            mock.logout()
            real.signIn(request).getOrThrow()
            (retiredClient.messages as MutableStateFlow).value = mapOf(friend to listOf(
                ChatMessage(1L, friend, MessageAuthor.Contact, "Late mock reply", 1L),
            ))
            real.loadConversation(friend)
            assertTrue(real.conversation(friend).first().messages.isEmpty())
        } finally {
            mock.logout()
            real.logout()
            mockScope.cancel()
            realScope.cancel()
        }
    }

    @Test
    fun queuedRememberedLoginCannotReplaceAnInteractiveSession() = runBlocking {
        val backing = RecordingMsnpClient()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = object : MsnpClient by backing {
            override suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot> {
                started.complete(Unit)
                release.await()
                return backing.signIn(request)
            }
        }
        val repo = MessengerRepository(client)
        val chosen = SignInRequest("chosen@example.com", "password",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))
        try {
            val interactive = async { repo.signIn(chosen) }
            started.await()
            val stale = async(start = CoroutineStart.UNDISPATCHED) {
                repo.signIn(chosen.copy(email = "previous@example.com"), isRememberedCurrent = { true }) {
                    error("Stale session was activated")
                }
            }
            release.complete(Unit)
            interactive.await().getOrThrow()
            assertTrue(stale.await().isFailure)
            assertEquals(listOf(chosen), backing.signInRequests)
            assertEquals(chosen.email, repo.profile.value.email)

            repo.logout()
            assertTrue(repo.signIn(chosen, isRememberedCurrent = { true }) { false }.isFailure)
            assertTrue(!repo.hasSignedInSession)
            assertEquals("", repo.profile.value.email)
        } finally {
            release.complete(Unit)
            repo.retire()
        }
    }

    @Test
    fun changedRememberedSelectionCannotRevokeOtherSessions() = runBlocking {
        val client = RecordingMsnpClient().apply { sessions.value = listOf(currentSession) + remoteSessions }
        val repo = MessengerRepository(client, signOutOtherSessionsOnSignIn = { true })
        val request = SignInRequest("previous@example.com", "password",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))
        try {
            var activated = false
            val result = repo.signIn(request, isRememberedCurrent = { false }) {
                activated = true
                true
            }
            assertEquals(listOf(request), client.signInRequests)
            assertTrue(result.isFailure)
            assertTrue(client.loggedOutSessions.isEmpty())
            assertTrue(!activated)
            assertTrue(!repo.hasSignedInSession)
        } finally { repo.retire() }
    }

    @Test
    fun queuedMessageIsDiscardedWhenTheSameClientSignsInAgain() = runBlocking {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = Executor { tasks.add(it) }.asCoroutineDispatcher()
        val client = MockMsnp18Client(signInDelayMillis = 0)
        val repo = MessengerRepository(client, scope = CoroutineScope(dispatcher))
        val request = SignInRequest("first@example.com", "password",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))
        try {
            repo.signIn(request).getOrThrow()
            repo.sendMessage("selin", "previous account")
            repo.logout()
            val second = request.copy(email = "second@example.com")
            repo.signIn(second).getOrThrow()
            repo.sendMessage("selin", "previous session")
            repo.logout()
            repo.signIn(second).getOrThrow()
            repo.sendMessage("selin", "current account")
            while (true) (tasks.poll() ?: break).run()
            val delivered = client.messages.value.getValue("selin").filter { it.author == MessageAuthor.Me }
            assertEquals(listOf("current account"), delivered.map { it.text })
        } finally { repo.retire() }
    }

    @Test
    fun notificationDeliveryHoldsItsAccountUntilSendCompletesAndRejectsStaleActions() = runBlocking {
        val backing = RecordingMsnpClient()
        val sending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reads = mutableListOf<String>()
        val client = object : MsnpClient by backing {
            override fun markRead(contactId: String) { reads += contactId }

            override suspend fun sendStyledMessage(
                contactId: String, text: String, renderedEmoticonRanges: List<MsnEmoticonRange>?,
                style: NativeTextStyle,
            ): Result<Unit> {
                sending.complete(Unit)
                release.await()
                return Result.success(Unit)
            }
        }
        val repo = MessengerRepository(client)
        val first = SignInRequest("first@example.com", "password",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))
        val friend = "peer@example.com"
        try {
            repo.signIn(first).getOrThrow()
            backing.contacts.value = listOf(Contact(friend, "Peer", friend, Presence.Online))
            val owner = requireNotNull(repo.signedInAccountIdentity).storageKey
            val reply = async { repo.sendNotificationAction(owner, friend, "hello") }
            sending.await()
            val switch = async(start = CoroutineStart.UNDISPATCHED) {
                repo.signIn(first.copy(email = "second@example.com"))
            }
            assertEquals(listOf(first), backing.signInRequests)
            release.complete(Unit)
            assertEquals(friend, reply.await().getOrThrow()?.id)
            switch.await().getOrThrow()
            assertTrue(repo.sendNotificationAction(owner, friend, "stale").isFailure)
            assertTrue(repo.sendNotificationAction(owner, friend, null).isFailure)
            assertEquals(listOf(friend), reads)
        } finally {
            release.complete(Unit)
            repo.retire()
        }
    }

    @Test
    fun delayedMockNudgeCannotAppearInTheNextAccountsConversation() = runBlocking {
        val client = MockMsnp18Client(scope = CoroutineScope(Dispatchers.Unconfined), signInDelayMillis = 0)
        val first = SignInRequest("first@example.com", "password",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))
        try {
            client.signIn(first).getOrThrow()
            client.sendNudgeWithResult("selin").getOrThrow()
            client.close()
            client.signIn(first.copy(email = "second@example.com")).getOrThrow()
            client.sendMessage("selin", "hello")
            val replies = withTimeout(5_000) {
                client.messages.first { entries -> entries["selin"].orEmpty().any { it.author == MessageAuthor.Contact } }
            }
            assertTrue(replies["selin"].orEmpty().none(ChatMessage::isNudge))
        } finally {
            client.close()
        }
    }

    @Test
    fun incomingMessagesDuringAccountChangeOnlyReachTheNewHistory() = runBlocking {
        val backing = RecordingMsnpClient()
        val message = ChatMessage(1L, "friend@example.com", MessageAuthor.Contact, "Private", 1L)
        val client = object : MsnpClient by backing {
            override suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot> =
                backing.signIn(request).also {
                    if (request.email == "second@example.com") backing.publish(message)
                }
        }
        val repository = MessengerRepository(client, scope = CoroutineScope(Dispatchers.Unconfined))
        val first = SignInRequest("first@example.com", "password",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))
        try {
            repository.signIn(first).getOrThrow()
            repository.signIn(first.copy(email = "second@example.com")).getOrThrow()
            assertEquals(listOf(message), repository.conversation(message.contactId).first().messages)
            repository.logout()
            assertTrue(repository.conversation(message.contactId).first().messages.isEmpty())
            repository.signIn(first).getOrThrow()
            assertTrue(repository.conversation(message.contactId).first().messages.isEmpty())
        } finally {
            repository.retire()
        }
    }

    @Test
    fun retiringDuringSignInPreventsLatePublicationAndReleasesCollectors() = runBlocking {
        val backing = RecordingMsnpClient()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = object : MsnpClient by backing {
            override suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot> {
                started.complete(Unit)
                release.await()
                return backing.signIn(request)
            }
        }
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = MessengerRepository(client, scope = parent)
        val request = SignInRequest("old@example.com", "password",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))
        try {
            val signIn = async { repository.signIn(request) }
            started.await()
            repository.retire()
            release.complete(Unit)
            assertTrue(signIn.await().isFailure)
            assertTrue(!repository.hasSignedInSession)
            assertEquals("", repository.profile.value.email)
            assertEquals(0, backing.events.subscriptionCount.value)
            assertTrue(parent.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
            assertTrue(repository.signIn(request).isFailure)
        } finally {
            release.complete(Unit)
            repository.retire()
            parent.cancel()
        }
    }

    @Test
    fun sameEmailKeepsMockAndRealConversationHistoryAndDraftsSeparate() = runBlocking {
        val history = org.aralozkaya.msngr.data.InMemoryConversationHistoryStore()
        val drafts = org.aralozkaya.msngr.data.ConversationDraftStore()
        val container = AppContainer(
            clientFactory = { RecordingMsnpClient() },
            conversationHistoryStore = history,
            conversationDraftStore = drafts,
        )
        val request = SignInRequest("same@example.com", "password",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))
        val friend = "friend@example.com"
        val mockMessage = ChatMessage(1L, friend, MessageAuthor.Me,
            "Mock history", 1L)
        val realMessage = mockMessage.copy(id = 2L, text = "Real history", timestampMillis = 2L)
        try {
            val mock = container.repositoryFor(true)
            mock.signIn(request).getOrThrow()
            assertNull(container.repositoryForRestore(false))
            mock.updateDraft(friend, "Mock draft")
            history.merge(mapOf(friend to listOf(mockMessage)))

            mock.logout()
            val real = requireNotNull(container.repositoryForRestore(false))
            real.signIn(request).getOrThrow()
            assertTrue(real.drafts.value.isEmpty())
            real.loadConversation(friend)
            assertTrue(real.conversation(friend).first().messages.isEmpty())
            real.updateDraft(friend, "Real draft")
            mock.updateDraft(friend, "Stale edit")
            assertTrue(mock.drafts.value.isEmpty())
            assertEquals("Real draft", real.drafts.value[friend])
            history.merge(mapOf(friend to listOf(realMessage)))

            val restoredMock = container.repositoryFor(true)
            restoredMock.signIn(request).getOrThrow()
            restoredMock.loadConversation(friend)
            assertEquals("Mock draft", restoredMock.drafts.value[friend])
            assertEquals(listOf(mockMessage), restoredMock.conversation(friend).first().messages)

            val restoredReal = container.repositoryFor(false)
            restoredReal.signIn(request).getOrThrow()
            restoredReal.loadConversation(friend)
            assertEquals("Real draft", restoredReal.drafts.value[friend])
            assertEquals(listOf(realMessage), restoredReal.conversation(friend).first().messages)
        } finally {
            container.messengerRepository.logout()
        }
    }

    @Test
    fun sameEmailOnDifferentServicesDoesNotReuseConversationState() = runBlocking {
        val history = org.aralozkaya.msngr.data.InMemoryConversationHistoryStore()
        val repository = MessengerRepository(RecordingMsnpClient(), historyStore = history)
        val first = SignInRequest("same@example.com", "password",
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))
        val second = first.copy(serverConfig = ServerPresets.resolveForProvider(
            ProviderOptions.CROSSTALK, ServerOverrides()))
        val friend = "friend@example.com"
        val message = ChatMessage(1L, friend, MessageAuthor.Me, "First service", 1L)
        try {
            repository.signIn(first).getOrThrow()
            repository.updateDraft(friend, "First draft")
            history.merge(mapOf(friend to listOf(message)))
            repository.signIn(second).getOrThrow()
            repository.loadConversation(friend)
            assertTrue(repository.drafts.value.isEmpty())
            assertTrue(repository.conversation(friend).first().messages.isEmpty())
            repository.signIn(first).getOrThrow()
            repository.loadConversation(friend)
            assertEquals("First draft", repository.drafts.value[friend])
            assertEquals(listOf(message), repository.conversation(friend).first().messages)
        } finally {
            repository.logout()
        }
    }

    @Test
    fun groupOnlyMemberResolvesForChatAndProfileWithoutBecomingAContact() = runBlocking {
        val client = RecordingMsnpClient()
        val member = GroupMember("member@example.com", "Group member", Presence.Away)
        val group = Contact("group@live.com", "Test", "group@live.com", Presence.Online,
            isGroup = true, members = listOf(member))
        client.contacts.value = listOf(group)
        val repository = MessengerRepository(client)
        try {
            val resolved = requireNotNull(repository.contactSnapshot(member.email))
            assertEquals("Group member", resolved.displayName)
            assertTrue(!resolved.isForwardListContact && !resolved.isGroup)
            assertEquals(resolved, repository.contact(member.email).first())
            client.contacts.value = listOf(group.copy(members = listOf(member.copy(avatarUri = "data:picture"))))
            assertEquals("data:picture", repository.contact(member.email).first()?.avatarUri)
            assertEquals(listOf(group.id), repository.contacts.value.map(Contact::id))
            val direct = resolved.copy(nickname = "Saved nickname", isBlocked = true, avatarUri = "data:direct")
            client.contacts.value += direct
            assertEquals(direct, repository.contactSnapshot(member.email))
            assertEquals(direct, repository.contact(member.email).first())
        } finally {
            repository.logout()
        }
    }

    @Test
    fun ownGroupMemberUsesCurrentProfilePicture() = runBlocking {
        val repository = MessengerRepository(RecordingMsnpClient())
        try {
            repository.setLocalProfile(UserProfileSnapshot("Me", "me@example.com", Presence.Online,
                avatarUri = "data:self", statusMessage = "My status"))
            val contact = requireNotNull(repository.contact("me@example.com").first())
            assertEquals("Me", contact.displayName)
            assertEquals("data:self", contact.avatarUri)
            assertEquals("My status", contact.statusMessage)
        } finally {
            repository.logout()
        }
    }

    @Test
    fun readActionsArePublishedByTheRepositoryContactStream() = runBlocking {
        val readContacts = mutableListOf<String>()
        val repository = MessengerRepository(
            MockMsnp18Client(signInDelayMillis = 0),
            onConversationRead = { readContacts += it },
        )
        try {
            repository.signIn(SignInRequest(
                email = MockServerData.ACCOUNT_EMAIL,
                password = MockServerData.ACCOUNT_PASSWORD,
                serverConfig = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()),
            )).getOrThrow()
            val contact = repository.contacts.value.first()
            repository.markRead(contact.id)
            assertEquals(0, repository.contacts.value.first { it.id == contact.id }.unreadCount)
            assertEquals(listOf(contact.id), readContacts)
            // An already-read chat may still have a notification after process restart.
            repository.markRead(contact.id)
            assertEquals(listOf(contact.id, contact.id), readContacts)
            repository.markUnread(contact.id)
            assertEquals(0, repository.contacts.value.first { it.id == contact.id }.unreadCount)
            assertTrue(repository.contacts.value.first { it.id == contact.id }.isUnread)
            assertEquals(2, readContacts.size)
        } finally {
            repository.logout()
        }
    }

    @Test
    fun restoredNicknameAndStatusArePublishedAndLaterNicknameEditsReachMsnp() = runBlocking {
        val client = RecordingMsnpClient()
        val repository = MessengerRepository(client)
        val restoredProfile = UserProfileSnapshot(
            username = MockServerData.ACCOUNT_NICKNAME,
            email = MockServerData.ACCOUNT_EMAIL,
            presence = Presence.Online,
            statusMessage = MockServerData.ACCOUNT_STATUS_MESSAGE,
        )

        val displayPicture = byteArrayOf(1, 2, 3, 4)
        repository.setLocalProfile(
            restoredProfile,
            publishStoredSettings = true,
            displayPictureBytes = displayPicture,
        )

        assertEquals(
            MockServerData.ACCOUNT_NICKNAME,
            withTimeout(1_000) { client.nicknameUpdates.receive() },
        )
        assertEquals(
            MockServerData.ACCOUNT_STATUS_MESSAGE,
            withTimeout(1_000) { client.statusMessageUpdates.receive() },
        )
        assertArrayEquals(
            displayPicture,
            withTimeout(1_000) { client.displayPictureUpdates.receive() }.image,
        )

        val updatedNickname = "Updated ${MockServerData.ACCOUNT_NICKNAME}"
        repository.updateProfile(updatedNickname, avatarUri = null)

        assertEquals(
            updatedNickname,
            withTimeout(1_000) { client.nicknameUpdates.receive() },
        )
        repository.logout()
    }

    @Test
    fun removingDisplayPicturePublishesNullBeforeUpdatingLocalProfile() = runBlocking {
        val client = RecordingMsnpClient()
        val repository = MessengerRepository(client)
        repository.setLocalProfile(
            UserProfileSnapshot(
                username = "Original",
                email = MockServerData.ACCOUNT_EMAIL,
                presence = Presence.Online,
                avatarUri = "content://old-picture",
            ),
        )

        repository.updateProfile("Original", avatarUri = null).getOrThrow()

        assertNull(withTimeout(1_000) { client.displayPictureUpdates.receive() }.image)
        assertNull(repository.profile.value.avatarUri)
        repository.logout()
    }

    @Test
    fun failedDisplayPictureUploadDoesNotPretendTheProfileWasSaved() = runBlocking {
        val client = RecordingMsnpClient().apply {
            displayPictureFailure = IOException("storage rejected upload")
        }
        val repository = MessengerRepository(client)
        val original = UserProfileSnapshot(
            username = "Original",
            email = MockServerData.ACCOUNT_EMAIL,
            presence = Presence.Online,
            avatarUri = "content://old-picture",
        )
        repository.setLocalProfile(original)

        val result = repository.updateProfile(
            username = "Original",
            avatarUri = "content://new-picture",
            displayPictureBytes = byteArrayOf(9, 8, 7),
        )

        assertTrue(result.isFailure)
        assertEquals(original, repository.profile.value)
        repository.logout()
    }

    @Test
    fun reselectingTheSameDocumentUriStillPublishesNewPictureBytes() = runBlocking {
        val client = RecordingMsnpClient()
        val repository = MessengerRepository(client)
        val uri = "content://selected-picture"
        val bytes = byteArrayOf(7, 8, 9)
        repository.setLocalProfile(
            UserProfileSnapshot(
                username = "Original",
                email = MockServerData.ACCOUNT_EMAIL,
                presence = Presence.Online,
                avatarUri = uri,
            ),
        )

        repository.updateProfile("Original", uri, bytes).getOrThrow()

        assertArrayEquals(
            bytes,
            withTimeout(1_000) { client.displayPictureUpdates.receive() }.image,
        )
        repository.logout()
    }

    @Test
    fun reconnectReusesCredentialsAndPreservesLatestPresence() = runBlocking {
        val client = RecordingMsnpClient()
        val repository = MessengerRepository(client)
        val request = SignInRequest(
            email = MockServerData.ACCOUNT_EMAIL,
            password = MockServerData.ACCOUNT_PASSWORD,
            serverConfig = ServerPresets.resolveForProvider(
                ProviderOptions.ESCARGOT,
                ServerOverrides(),
            ),
            initialPresence = Presence.Online,
        )

        repository.signIn(request).getOrThrow()
        repository.setPresence(Presence.Busy)
        repository.reconnect().getOrThrow()

        assertEquals(2, client.signInRequests.size)
        assertEquals(MockServerData.ACCOUNT_PASSWORD, client.signInRequests.last().password)
        assertEquals(Presence.Busy, client.signInRequests.last().initialPresence)
        repository.logout()
    }

    @Test
    fun repeatedSignInForTheSameHealthySessionDoesNotRestartTheClient() = runBlocking {
        val client = RecordingMsnpClient()
        val repository = MessengerRepository(client)
        val request = SignInRequest(
            email = MockServerData.ACCOUNT_EMAIL,
            password = MockServerData.ACCOUNT_PASSWORD,
            serverConfig = ServerPresets.resolveForProvider(
                ProviderOptions.ESCARGOT,
                ServerOverrides(),
            ),
        )

        val firstProfile = repository.signIn(request).getOrThrow()
        val restoredProfile = repository.signIn(request).getOrThrow()

        assertEquals(firstProfile, restoredProfile)
        assertEquals(listOf(request), client.signInRequests)
        repository.logout()
    }

    @Test
    fun failedReplacementSignInPublishesAConnectionIssue() = runBlocking {
        val client = RecordingMsnpClient()
        val repository = MessengerRepository(client)
        val request = SignInRequest(
            email = MockServerData.ACCOUNT_EMAIL,
            password = MockServerData.ACCOUNT_PASSWORD,
            serverConfig = ServerPresets.resolveForProvider(
                ProviderOptions.ESCARGOT,
                ServerOverrides(),
            ),
        )
        repository.signIn(request).getOrThrow()
        client.signInFailure = IOException("Connection reset during sign-in")

        val result = repository.signIn(request.copy(initialPresence = Presence.Busy))

        assertTrue(result.isFailure)
        assertEquals("Connection reset during sign-in", repository.connectionIssue.value)
        repository.logout()
    }

    @Test
    fun failedHistoryErasePreservesLiveMessagesUntilRetrySucceeds() = runBlocking {
        val backing = InMemoryConversationHistoryStore()
        var failErase = true
        val history = object : ConversationHistoryStore by backing {
            override fun clearAll() {
                check(!failErase)
                backing.clearAll()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val client = RecordingMsnpClient()
            val repository = MessengerRepository(client, scope = scope, historyStore = history)
            repository.signIn(SignInRequest(MockServerData.ACCOUNT_EMAIL, MockServerData.ACCOUNT_PASSWORD,
                ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            val message = ChatMessage(1, "friend", MessageAuthor.Contact, "Still here", 123)
            client.publish(message)
            withTimeout(2_000) { backing.messages.first { it["friend"] == listOf(message) } }

            assertTrue(runCatching { repository.clearConversationHistory() }.isFailure)
            assertEquals(listOf(message), client.messages.value["friend"])
            assertEquals(listOf(message), backing.messages.value["friend"])

            failErase = false
            repository.clearConversationHistory()
            assertTrue(client.messages.value.isEmpty())
            assertTrue(backing.messages.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun conversationHistorySurvivesReconnectAndDeleteAllClearsIt() = runBlocking {
        val client = RecordingMsnpClient()
        val repository = MessengerRepository(client)
        val request = SignInRequest(
            email = MockServerData.ACCOUNT_EMAIL,
            password = MockServerData.ACCOUNT_PASSWORD,
            serverConfig = ServerPresets.resolveForProvider(
                ProviderOptions.ESCARGOT,
                ServerOverrides(),
            ),
        )
        val message = ChatMessage(
            id = 42,
            contactId = "friend@example.com",
            author = MessageAuthor.Contact,
            text = "Still here",
            timestampMillis = 1_234,
        )
        repository.signIn(request).getOrThrow()
        client.publish(message)
        withTimeout(1_000) {
            repository.conversation(message.contactId).first { it.messages == listOf(message) }
        }

        repository.reconnect().getOrThrow()

        assertEquals(
            listOf(message),
            repository.conversation(message.contactId).first().messages,
        )
        assertTrue(repository.conversation(message.contactId).first().isHistoryLoaded)

        repository.clearConversationHistory()

        assertTrue(repository.conversation(message.contactId).first().messages.isEmpty())
        assertTrue(repository.conversation(message.contactId).first().isHistoryLoaded)
        assertTrue(client.messages.value.isEmpty())
        repository.logout()
    }

    @Test
    fun deletingOneConversationDoesNotRestoreOldSessionMessagesOrEraseOthers() = runBlocking {
        val client = RecordingMsnpClient()
        val repository = MessengerRepository(client)
        try {
            repository.signIn(SignInRequest(
                email = MockServerData.ACCOUNT_EMAIL,
                password = MockServerData.ACCOUNT_PASSWORD,
                serverConfig = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()),
            )).getOrThrow()
            val erased = ChatMessage(
                id = 41,
                contactId = "friend@example.com",
                author = MessageAuthor.Contact,
                text = "Erase me",
                timestampMillis = 1_000,
            )
            val kept = erased.copy(id = 42, contactId = "other@example.com", text = "Keep me")
            client.publish(erased)
            client.publish(kept)
            withTimeout(1_000) {
                repository.conversation(kept.contactId).first { it.messages == listOf(kept) }
            }

            repository.clearConversationHistory(erased.contactId)

            assertTrue(repository.conversation(erased.contactId).first().messages.isEmpty())
            assertTrue(repository.conversation(erased.contactId).first().isHistoryLoaded)
            assertTrue(client.messages.value[erased.contactId].isNullOrEmpty())
            assertEquals(listOf(kept), repository.conversation(kept.contactId).first().messages)
            val newMessage = erased.copy(id = 43, text = "New message", timestampMillis = 2_000)
            client.publish(newMessage)
            withTimeout(1_000) {
                repository.conversation(erased.contactId).first { it.messages == listOf(newMessage) }
            }
            repository.reconnect().getOrThrow()
            assertEquals(listOf(newMessage), repository.conversation(erased.contactId).first().messages)
            assertEquals(listOf(kept), repository.conversation(kept.contactId).first().messages)
        } finally {
            repository.logout()
        }
    }

    @Test
    fun immediateNudgeReportsClientDeliveryFailure() = runBlocking {
        val client = RecordingMsnpClient().apply {
            nudgeFailure = IOException("switchboard rejected nudge")
        }
        val repository = MessengerRepository(client)

        val result = repository.sendNudgeNow("friend@example.com")

        assertTrue(result.isFailure)
        repository.logout()
    }

    @Test
    fun retryFindsRestoredFailureAndPreservesItsOriginalContent() = runBlocking {
        val history = org.aralozkaya.msngr.data.InMemoryConversationHistoryStore()
        history.selectAccount(MockServerData.ACCOUNT_EMAIL)
        val failed = ChatMessage(
            id = 42L,
            contactId = "friend@example.com",
            author = MessageAuthor.Me,
            text = "[b]hello[/b] :-)",
            timestampMillis = 123L,
            renderedEmoticonRanges = emptyList(),
            deliveryError = "Disconnected",
        )
        history.merge(mapOf(failed.contactId to listOf(failed)))
        val client = RecordingMsnpClient()
        val repository = MessengerRepository(
            client = client,
            historyStore = history,
            messengerPlusEnabled = { false },
        )
        try {
            repository.retryMessage(failed.contactId, failed.id)
            assertEquals(failed, withTimeout(1_000) { client.retriedMessages.receive() })
            assertTrue(client.sentMessages.tryReceive().isFailure)
        } finally {
            repository.logout()
        }
    }

    @Test
    fun disabledMessengerPlusStripsFormattingBeforeRepositorySends() = runBlocking {
        val client = RecordingMsnpClient()
        val repository = MessengerRepository(
            client = client,
            messengerPlusEnabled = { false },
        )

        repository.sendMessageNow(
            contactId = "friend@example.com",
            text = "[b][c=4]hello[/c][/b]",
        ).getOrThrow()

        assertEquals(
            SentMessage("friend@example.com", "hello", null),
            withTimeout(1_000) { client.sentMessages.receive() },
        )
        repository.logout()
    }

    @Test
    fun updateStatusMessageAwaitsPublishAndRollsBackOnFailure() = runBlocking {
        val client = RecordingMsnpClient()
        val repository = MessengerRepository(client)
        val request = SignInRequest(
            MockServerData.ACCOUNT_EMAIL,
            MockServerData.ACCOUNT_PASSWORD,
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()),
        )
        repository.signIn(request).getOrThrow()

        repository.updateStatusMessage("Busy right now").getOrThrow()
        assertEquals("Busy right now", withTimeout(1_000) { client.statusMessageUpdates.receive() })
        assertEquals("Busy right now", repository.profile.value.statusMessage)

        client.personalMessageFailure = IOException("storage offline")
        val failed = repository.updateStatusMessage("Failed edit")

        assertTrue(failed.isFailure)
        assertEquals("Busy right now", repository.profile.value.statusMessage)
        assertTrue(client.statusMessageUpdates.isEmpty)
        repository.logout()
    }

    @Test
    fun ownPersonalStatusChangedEventUpdatesProfileWithoutRePublishing() = runBlocking {
        val client = RecordingMsnpClient()
        val repository = MessengerRepository(client)
        val serverConfig = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides())
        val request = SignInRequest(
            MockServerData.ACCOUNT_EMAIL,
            MockServerData.ACCOUNT_PASSWORD,
            serverConfig,
        )
        repository.signIn(request).getOrThrow()

        client.events.emit(
            MessengerEvent.OwnPersonalStatusChanged(
                AccountIdentity.forServer(MockServerData.ACCOUNT_EMAIL, serverConfig, useMockServer = false),
                "Updated from PC",
            ),
        )

        withTimeout(1_000) {
            repository.profile.first { it.statusMessage == "Updated from PC" }
        }
        assertEquals("Updated from PC", repository.profile.value.statusMessage)
        assertTrue(client.statusMessageUpdates.isEmpty)
        repository.logout()
    }

    @Test
    fun setLocalProfileCanSkipRepublishingStatusWhenPreservingPendingLocalStatus() = runBlocking {
        val client = RecordingMsnpClient()
        val repository = MessengerRepository(client)
        val request = SignInRequest(
            MockServerData.ACCOUNT_EMAIL,
            MockServerData.ACCOUNT_PASSWORD,
            ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()),
        )
        repository.signIn(request).getOrThrow()

        repository.setLocalProfile(
            repository.profile.value.copy(
                username = "Restored",
                statusMessage = "Pending offline edit",
            ),
            publishStoredSettings = true,
            publishStatusMessage = false,
        )

        assertEquals("Restored", withTimeout(1_000) { client.nicknameUpdates.receive() })
        assertTrue(client.statusMessageUpdates.isEmpty)
        assertEquals("Pending offline edit", repository.profile.value.statusMessage)
        repository.logout()
    }

    @Test
    fun ownPresenceChangedEventUpdatesProfileAndCallbackWithoutRePublishing() = runBlocking {
        val client = RecordingMsnpClient()
        val persisted = mutableListOf<Pair<AccountIdentity, Presence>>()
        val repository = MessengerRepository(
            client = client,
            onOwnPresenceChanged = { account, presence -> persisted += account to presence },
        )
        val serverConfig = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides())
        val account = AccountIdentity.forServer(MockServerData.ACCOUNT_EMAIL, serverConfig, useMockServer = false)
        val otherAccount = AccountIdentity.forServer("other@example.com", serverConfig, useMockServer = false)
        repository.signIn(
            SignInRequest(
                MockServerData.ACCOUNT_EMAIL,
                MockServerData.ACCOUNT_PASSWORD,
                serverConfig,
                initialPresence = Presence.Online,
            ),
        ).getOrThrow()

        client.events.emit(MessengerEvent.OwnPresenceChanged(account, Presence.Busy))
        withTimeout(1_000) {
            repository.profile.first { it.presence == Presence.Busy }
        }
        client.events.emit(MessengerEvent.OwnPresenceChanged(otherAccount, Presence.Offline))
        client.events.emit(MessengerEvent.OwnPresenceChanged(account, Presence.Offline))
        withTimeout(1_000) {
            repository.profile.first { it.presence == Presence.Offline }
        }

        assertEquals(listOf(account to Presence.Busy, account to Presence.Offline), persisted)
        assertTrue(client.presenceUpdates.isEmpty)
        repository.logout()
    }

    @Test
    fun conversationRepliedElsewhereClearsPendingEffectsAndDismissesConversation() = runBlocking {
        val client = RecordingMsnpClient()
        val dismissed = mutableListOf<String>()
        val repository = MessengerRepository(
            client = client,
            onConversationRead = { dismissed += it },
        )
        val serverConfig = ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides())
        repository.signIn(
            SignInRequest(
                MockServerData.ACCOUNT_EMAIL,
                MockServerData.ACCOUNT_PASSWORD,
                serverConfig,
            ),
        ).getOrThrow()

        client.events.emit(MessengerEvent.IncomingNudge("peer@example.com"))
        withTimeout(1_000) {
            repository.pendingNudgeShakes.first { "peer@example.com" in it }
        }

        client.events.emit(MessengerEvent.ConversationRepliedElsewhere("peer@example.com"))
        withTimeout(1_000) {
            repository.pendingNudgeShakes.first { "peer@example.com" !in it }
        }
        assertEquals(listOf("peer@example.com"), dismissed)
        repository.logout()
    }
}

private class RecordingMsnpClient : MsnpClient {
    override val contacts = MutableStateFlow<List<Contact>>(emptyList())
    private val mutableMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    override val messages: StateFlow<Map<String, List<ChatMessage>>> = mutableMessages
    override val typing: StateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap())
    override val events = MutableSharedFlow<MessengerEvent>()
    override val sessions = MutableStateFlow<List<org.aralozkaya.msngr.data.AccountSession>>(emptyList())
    val loggedOutSessions = mutableListOf<String>()
    val sessionActions = mutableListOf<String>()
    var logoutFailure: Exception? = null
    val nicknameUpdates = Channel<String>(Channel.UNLIMITED)
    val presenceUpdates = Channel<Presence>(Channel.UNLIMITED)
    val statusMessageUpdates = Channel<String>(Channel.UNLIMITED)
    val displayPictureUpdates = Channel<DisplayPictureUpdate>(Channel.UNLIMITED)
    val sentMessages = Channel<SentMessage>(Channel.UNLIMITED)
    val retriedMessages = Channel<ChatMessage>(Channel.UNLIMITED)
    val signInRequests = mutableListOf<SignInRequest>()
    var signInFailure: Exception? = null
    var displayPictureFailure: Exception? = null
    var personalMessageFailure: Exception? = null
    var nudgeFailure: Exception? = null

    override suspend fun signIn(request: SignInRequest): Result<UserProfileSnapshot> {
        signInRequests += request
        signInFailure?.let { return Result.failure(it) }
        mutableMessages.value = emptyMap()
        return Result.success(
            UserProfileSnapshot(
                username = request.email.substringBefore('@'),
                email = request.email,
                presence = request.initialPresence,
            ),
        )
    }

    override suspend fun changePresence(presence: Presence) {
        presenceUpdates.send(presence)
    }

    override suspend fun changeNickname(nickname: String) {
        nicknameUpdates.send(nickname)
    }

    override suspend fun changePersonalMessage(statusMessage: String) {
        personalMessageFailure?.let { throw it }
        statusMessageUpdates.send(statusMessage)
    }

    override suspend fun changeDisplayPicture(image: ByteArray?) {
        displayPictureFailure?.let { throw it }
        displayPictureUpdates.send(DisplayPictureUpdate(image))
    }

    override suspend fun sendMessage(
        contactId: String,
        text: String,
        renderedEmoticonRanges: List<MsnEmoticonRange>?,
    ) {
        sentMessages.send(SentMessage(contactId, text, renderedEmoticonRanges))
    }

    override suspend fun retryMessage(message: ChatMessage): Result<Unit> {
        retriedMessages.send(message)
        return Result.success(Unit)
    }

    override suspend fun sendTyping(contactId: String) = Unit

    override suspend fun sendNudge(contactId: String) {
        nudgeFailure?.let { throw it }
    }

    override suspend fun blockContact(contactId: String) = Unit

    override suspend fun unblockContact(contactId: String) = Unit

    override fun markRead(contactId: String) = Unit
    override fun markUnread(contactId: String) = Unit

    override fun clearMessages(contactId: String) {
        mutableMessages.value = mutableMessages.value - contactId
    }

    override fun clearMessages() {
        mutableMessages.value = emptyMap()
    }

    override fun close() { sessionActions += "close"; sessions.value = emptyList() }

    override suspend fun logOutSession(sessionId: String) {
        logoutFailure?.let { throw it }
        loggedOutSessions += sessionId
        sessionActions += sessionId
        sessions.value = sessions.value.filterNot { it.id == sessionId }
    }

    fun publish(message: ChatMessage) {
        mutableMessages.value = mutableMessages.value +
            (message.contactId to (mutableMessages.value[message.contactId].orEmpty() + message))
    }
}

private data class DisplayPictureUpdate(val image: ByteArray?)

private data class SentMessage(
    val contactId: String,
    val text: String,
    val renderedEmoticonRanges: List<MsnEmoticonRange>?,
)
