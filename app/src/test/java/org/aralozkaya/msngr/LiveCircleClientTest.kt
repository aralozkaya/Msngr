package org.aralozkaya.msngr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.aralozkaya.msngr.data.AddressBookSnapshot
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.GroupInkFormat
import org.aralozkaya.msngr.data.GroupMember
import org.aralozkaya.msngr.data.LiveMsnp18Client
import org.aralozkaya.msngr.data.LocalDisplayPicture
import org.aralozkaya.msngr.data.MessageAuthor
import org.aralozkaya.msngr.data.MessengerEvent
import org.aralozkaya.msngr.data.MsnpCircles
import org.aralozkaya.msngr.data.MsnpP2pSession
import org.aralozkaya.msngr.data.NudgeCooldownException
import org.aralozkaya.msngr.data.NudgeRateLimiter
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.data.SsoToken
import org.aralozkaya.msngr.data.StorageProfile
import org.aralozkaya.msngr.data.SwitchboardMimeMessage
import org.aralozkaya.msngr.data.WinkStore
import org.aralozkaya.msngr.data.groupFeatures
import org.aralozkaya.msngr.data.parseCircleEnvelope
import org.aralozkaya.msngr.data.parseSwitchboardMime
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.protocol.InkDrawing
import org.aralozkaya.msngr.protocol.InkPoint
import org.aralozkaya.msngr.protocol.InkStroke
import org.aralozkaya.msngr.protocol.MsnDisplayPicture
import org.aralozkaya.msngr.protocol.MsnInkCodec
import org.aralozkaya.msngr.protocol.Msnp18Protocol
import org.aralozkaya.msngr.protocol.MsnpCommand
import org.aralozkaya.msngr.protocol.MsnpTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LiveCircleClientTest {
    private val me = "me@example.com"
    private val groupId = "00000000-0000-0000-0009-123456789abc@live.com"
    private val otherGroupId = "00000000-0000-0000-0009-123456789abd@live.com"
    private val group = Contact(groupId, "Test", groupId, Presence.Online, isGroup = true)

    @Test fun styledEmotionSoundsUseNormalGroupTextAndDoNotRequireClientCapabilities() = runBlocking {
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = { error("Group emotion sounds use circle text") },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(group), circleTicket = "signed") },
            fetchStorageProfile = { _, _ -> StorageProfile() })
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            transport.incoming.send(roster(groupId, me, "alice@example.com"))
            withTimeout(2_000) { client.contacts.first { it.single().presence.isOnline } }
            val custom = org.aralozkaya.msngr.protocol.MsnEmotionSound.message(org.aralozkaya.msngr.protocol.EmotionSound("012345ABCDEF", "[b]Group sound[/b]"))
            val style = NativeTextStyle(italic = true, colorRgb = 0x123456)
            for (text in listOf("\u0004#[b][c=#FF6600]Group hello[/c][/b]", custom)) {
                client.sendStyledMessage(groupId, text, null, style).getOrThrow()
                val sent = parseCircleEnvelope(transport.sent.last { it.verb == "SDG" }.payload!!)!!
                assertEquals(text, sent.body.toString(Charsets.UTF_8))
                assertEquals(style, NativeTextStyle.fromFormatHeader(sent.headers["x-mms-im-format"]))
                transport.incoming.send(MsnpCircles.message(0, groupId, "alice@example.com", "peer", text, style = style))
                withTimeout(2_000) { client.messages.first { it[groupId]?.lastOrNull()?.author == org.aralozkaya.msngr.data.MessageAuthor.Contact } }
                val received = client.messages.value.getValue(groupId).last()
                assertEquals(text, received.text)
                assertEquals(style, received.nativeStyle)
                assertEquals("alice@example.com", received.senderEmail)
                assertNotNull(received.emotionSound)
                assertTrue(received.autoPlayEmotionSound)
            }
        } finally { client.close(); scope.cancel() }
    }

    @Test fun voiceClipsNeverOpenASwitchboardOrSubmitAGroupMessage() = runBlocking {
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = { error("Group voice clips must never open a switchboard") },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(group), circleTicket = "signed") },
            fetchStorageProfile = { _, _ -> StorageProfile() })
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(
                ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            val bytes = javaClass.getResourceAsStream("/voice-clips/native-siren.wav")!!.use { it.readBytes() }
            val clip = org.aralozkaya.msngr.protocol.MsnVoiceClip.parse(bytes)!!
            assertTrue(client.sendVoiceClip(groupId, clip).isFailure)
            assertTrue(client.messages.value[groupId].isNullOrEmpty())
            assertFalse(transport.sent.any { it.verb in setOf("MSG", "SDG", "UUM", "XFR") })
        } finally { client.close(); scope.cancel() }
    }

    @Test fun groupNudgeFloodIsSilentPerSenderAndKeepsItsHistory() = runBlocking {
        var time = 0L
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = { error("Group nudges must not open a switchboard") },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(group), circleTicket = "signed") },
            fetchStorageProfile = { _, _ -> StorageProfile() },
            nudgeLimiter = NudgeRateLimiter { time })
        val events = CopyOnWriteArrayList<MessengerEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { events += it } }
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            transport.incoming.send(roster(groupId, me, "alice@example.com", "bob@example.com"))
            withTimeout(2_000) { client.contacts.first { it.single().presence.isOnline } }
            client.sendNudgeWithResult(groupId).getOrThrow()
            assertTrue(client.sendNudgeWithResult(groupId).exceptionOrNull() is NudgeCooldownException)
            assertEquals(1, transport.sent.count { it.verb == "SDG" })
            var size = 1
            suspend fun receive(sender: String, type: String = "Nudge") {
                transport.incoming.send(MsnpCircles.message(0, groupId, sender, "endpoint", "Marker", type))
                size++
                withTimeout(2_000) { client.messages.first { it[groupId]?.size == size } }
            }
            receive("alice@example.com")
            receive("ALICE@example.com")
            time = 10_999
            receive("alice@example.com")
            receive("bob@example.com") // Alice's stream must not silence Bob.
            time += 11_000
            receive("alice@example.com")
            receive(me)
            receive("bob@example.com", "Text")
            val marker = client.messages.value.getValue(groupId).last().id
            withTimeout(2_000) { while (events.none { it == MessengerEvent.IncomingMessage(groupId, marker) }) yield() }
            assertEquals(7, client.messages.value.getValue(groupId).count { it.isNudge })
            assertEquals(3, events.count { it is MessengerEvent.IncomingNudge })
            assertEquals(6, client.contacts.value.single().unreadCount)
            assertTrue(client.sendNudgeWithResult(groupId).exceptionOrNull() is NudgeCooldownException)
        } finally { collector.cancel(); client.close(); scope.cancel() }
    }

    @Test fun groupOnlyMemberPicturesRefreshInEveryGroupWithoutAddingAContact() = runBlocking {
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val storageId = "00000000-0000-0000-0000-123456789abc"
        val member = GroupMember("member@example.com", "Member", storageId = storageId)
        val requestedUrls = CopyOnWriteArrayList<String>()
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = { error("Stored member pictures do not need a switchboard") },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(
                group.copy(members = listOf(member)),
                group.copy(id = otherGroupId, email = otherGroupId, members = listOf(member)),
            ), circleTicket = "signed") },
            fetchStorageProfile = { _, _ -> StorageProfile() },
            fetchDisplayPicture = { url, _ -> requestedUrls += url; byteArrayOf(1, 2, 3) })
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            client.refreshContactPicture(member.email)
            assertTrue(requestedUrls.any { it.endsWith("/storage/usertile/$storageId/static") })
            assertEquals(2, client.contacts.value.size)
            assertTrue(client.contacts.value.all { it.isGroup && it.members.single().avatarUri != null })
        } finally { client.close(); scope.cancel() }
    }

    @Test fun groupsAuthenticateAndRouteWithoutSwitchboards() = runBlocking {
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = { error("A circle must not open a switchboard") },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(group, group.copy(id = otherGroupId, email = otherGroupId)), circleTicket = "signed") },
            fetchStorageProfile = { _, _ -> StorageProfile() })
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            assertTrue(transport.sent.any { it.verb == "USR" && it.parameters.take(2) == listOf("SHA", "A") })
            val subscriptions = transport.sent.filter { it.verb == "ADL" }
            assertFalse(subscriptions.first().payload!!.toString(Charsets.UTF_8).contains(groupId.substringBefore('@')))
            assertTrue(subscriptions.last().payload!!.toString(Charsets.UTF_8).contains("t=\"9\""))
            transport.incoming.send(roster(groupId, me, "alice@example.com"))
            withTimeout(2000) { client.contacts.first { it.first().presence == Presence.Online } }
            client.sendMessageWithResult(groupId, "Hello Türkiye", null).getOrThrow()
            assertEquals("Hello Türkiye", parseCircleEnvelope(transport.sent.last { it.verb == "SDG" }.payload!!)!!.body.toString(Charsets.UTF_8))
            transport.incoming.send(MsnpCircles.message(0, groupId, "alice@example.com", "remote", "Alice's message"))
            withTimeout(2000) { client.messages.first { it[groupId]?.size == 2 } }
            val incoming = client.messages.value[groupId]!!.last()
            assertEquals("alice@example.com", incoming.senderEmail)
            assertEquals(groupId, incoming.contactId)
            assertNull(client.messages.value[otherGroupId])
            transport.incoming.send(MsnpCircles.message(0, otherGroupId, "bob@example.com", "remote", "Other conversation"))
            withTimeout(2000) { client.messages.first { it[otherGroupId]?.size == 1 } }
            assertEquals(2, client.messages.value[groupId]!!.size)
            transport.incoming.send(MsnpCommand("NLN", parameters = listOf("BSY", "1:alice@example.com;via=9:$groupId", "Alice", "0:0", "0")))
            withTimeout(2000) { client.contacts.first { it.first().members.any { member -> member.displayName == "Alice" } } }
            assertEquals(2, client.contacts.value.size)
            client.sendTyping(groupId)
            assertEquals("Typing", parseCircleEnvelope(transport.sent.last().payload!!)!!.headers["message-subtype"])
            client.blockContact(groupId)
            assertTrue(client.contacts.value.first().isBlocked)
            assertTrue(client.sendMessageWithResult(groupId, "Blocked", null).isFailure)
            client.unblockContact(groupId)
            assertFalse(client.contacts.value.first().isBlocked)
            transport.rejectSdg = true
            client.sendMessageWithResult(groupId, "Rejected", null)
            withTimeout(2000) { client.messages.first { it[groupId]!!.last().deliveryError != null } }
            Unit
        } finally { client.close(); scope.cancel() }
    }

    @Test fun groupHandwritingSendsAndReceivesOverCircleSdgWithoutSwitchboard() = runBlocking {
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = LiveMsnp18Client(
            scope = scope,
            transport = transport,
            switchboardTransportFactory = { error("Group handwriting must not open a switchboard") },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ ->
                AddressBookSnapshot(
                    listOf(
                        group.copy(
                            members = listOf(
                                GroupMember(me, "Me"),
                                GroupMember("alice@example.com", "Alice"),
                                GroupMember("bob@example.com", "Bob"),
                            ),
                        ),
                    ),
                    circleTicket = "signed",
                )
            },
            fetchStorageProfile = { _, _ -> StorageProfile() },
        )
        val events = CopyOnWriteArrayList<MessengerEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { events += it } }
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            transport.incoming.send(roster(groupId, me, "alice@example.com", "bob@example.com"))
            withTimeout(2_000) { client.contacts.first { it.single().presence.isOnline } }

            // 1. Send outgoing group handwriting
            transport.incoming.send(memberPresence("alice@example.com"))
            transport.incoming.send(memberPresence("bob@example.com"))
            withTimeout(2_000) { client.contacts.first { it.single().groupFeatures(me).inkFormat != null } }
            val outgoingDrawing = InkDrawing(
                canvasWidthPx = 220f,
                canvasHeightPx = 90f,
                strokes = listOf(
                    InkStroke(
                        points = listOf(InkPoint(15f, 20f), InkPoint(120f, 65f)),
                        colorRgb = 0x800080,
                        widthHimetric = 100,
                    ),
                ),
            )
            client.sendInkMessage(groupId, outgoingDrawing).getOrThrow()
            val sentSdg = transport.sent.last { it.verb == "SDG" }
            val sentEnvelope = requireNotNull(parseCircleEnvelope(sentSdg.payload!!))
            assertEquals(groupId, sentEnvelope.groupId)
            assertEquals("Ink", sentEnvelope.headers["message-type"])
            assertEquals(Msnp18Protocol.INK_ISF_CONTENT_TYPE, sentEnvelope.headers["content-type"])
            val decodedOutgoing = requireNotNull(MsnInkCodec.decodeMimeBody(sentEnvelope.headers["content-type"]!!, sentEnvelope.body))
            assertEquals(1, decodedOutgoing.strokes.size)
            assertEquals(0x800080, decodedOutgoing.strokes.single().colorRgb)

            // 2. Receive incoming ISF group handwriting from Alice
            val aliceDrawing = InkDrawing(
                canvasWidthPx = 240f,
                canvasHeightPx = 96f,
                strokes = listOf(
                    InkStroke(
                        points = listOf(InkPoint(20f, 30f), InkPoint(90f, 70f), InkPoint(180f, 40f)),
                        colorRgb = 0x008000,
                        widthHimetric = 200,
                    ),
                ),
            )
            transport.incoming.send(
                MsnpCircles.inkMessage(
                    transactionId = 0,
                    groupEmail = groupId,
                    senderEmail = "alice@example.com",
                    endpointId = "b343262e-a9e9-4335-a76e-d1e6df5923f2",
                    drawing = aliceDrawing,
                    useGif = false,
                    segment = 7,
                ),
            )
            withTimeout(2_000) { client.messages.first { it[groupId]?.size == 2 } }
            val aliceMsg = client.messages.value.getValue(groupId).last()
            assertEquals(MessageAuthor.Contact, aliceMsg.author)
            assertEquals("alice@example.com", aliceMsg.senderEmail)
            assertEquals("Alice", aliceMsg.senderName)
            assertTrue(aliceMsg.isInk)
            assertEquals(0x008000, aliceMsg.inkDrawing!!.strokes.single().colorRgb)
            assertEquals(200, aliceMsg.inkDrawing.strokes.single().widthHimetric)
            assertEquals(1, client.contacts.value.single().unreadCount)
            withTimeout(2_000) {
                while (events.none { it == MessengerEvent.IncomingMessage(groupId, aliceMsg.id) }) yield()
            }

            // 3. Receive incoming Fortified GIF group handwriting from Bob
            val bobDrawing = InkDrawing(
                canvasWidthPx = 200f,
                canvasHeightPx = 80f,
                strokes = listOf(
                    InkStroke(
                        points = listOf(InkPoint(10f, 15f), InkPoint(140f, 55f)),
                        colorRgb = 0xFF0000,
                        widthHimetric = 53,
                    ),
                ),
            )
            transport.incoming.send(
                MsnpCircles.inkMessage(
                    transactionId = 0,
                    groupEmail = groupId,
                    senderEmail = "bob@example.com",
                    endpointId = "b343262e-a9e9-4335-a76e-d1e6df5923f3",
                    drawing = bobDrawing,
                    useGif = true,
                    segment = 8,
                ),
            )
            withTimeout(2_000) { client.messages.first { it[groupId]?.size == 3 } }
            val bobMsg = client.messages.value.getValue(groupId).last()
            assertEquals(MessageAuthor.Contact, bobMsg.author)
            assertEquals("bob@example.com", bobMsg.senderEmail)
            assertEquals("Bob", bobMsg.senderName)
            assertTrue(bobMsg.isInk)
            assertEquals(0xFF0000, bobMsg.inkDrawing!!.strokes.single().colorRgb)
            assertNotNull(bobMsg.inkDrawing.fallbackGifBytes)
            assertEquals(2, client.contacts.value.single().unreadCount)

            // 4. Server rejection (933) marks the outgoing group handwriting message as failed
            transport.rejectSdg = true
            client.sendInkMessage(groupId, outgoingDrawing)
            withTimeout(2_000) { client.messages.first { it[groupId]!!.last().deliveryError != null } }
            Unit
        } finally {
            collector.cancel()
            client.close()
            scope.cancel()
        }
    }

    @Test fun availabilityExcludesSelfAndMembershipRefreshesOnlyAfterProtocolEvents() = runBlocking {
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fetches = AtomicInteger()
        val snapshot = AtomicReference(AddressBookSnapshot(listOf(group.copy(members = listOf(
            GroupMember(me), GroupMember("alice@example.com"),
        ))), circleTicket = "signed"))
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = { error("A removed circle must never open a switchboard") },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> fetches.incrementAndGet(); snapshot.get() },
            fetchStorageProfile = { _, _ -> StorageProfile() })
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            transport.incoming.send(roster(groupId, me))
            withTimeout(2000) { client.contacts.first { it.single().members.first().presence.isOnline } }
            assertEquals(Presence.Offline, client.contacts.value.single().presence)
            assertTrue(client.sendMessageWithResult(groupId, "Nobody else here", null).isFailure)
            assertTrue(client.sendNudgeWithResult(groupId).isFailure)
            client.sendTyping(groupId)
            assertTrue(transport.sent.none { it.verb == "SDG" })
            transport.incoming.send(roster(groupId, me, "alice@example.com"))
            withTimeout(2000) { client.contacts.first { it.single().presence.isOnline } }
            transport.incoming.send(MsnpCommand("FLN", parameters = listOf("1:alice@example.com;via=9:$groupId")))
            withTimeout(2000) { client.contacts.first { !it.single().presence.isOnline } }
            transport.incoming.send(MsnpCommand("NLN", parameters = listOf("NLN", "1:$me;via=9:$groupId", "Me", "0", "0")))
            withTimeout(2000) { client.contacts.first { it.single().members.first().displayName == "Me" } }
            assertEquals(Presence.Offline, client.contacts.value.single().presence)
            assertEquals(1, fetches.get())

            val notice = MsnpCommand("NOT", payload = "<NOTIFICATION siteid=\"45705\"><MSG><BODY>&lt;NotificationData&gt;&lt;CircleId&gt;${groupId.substringBefore('@')}&lt;/CircleId&gt;&lt;/NotificationData&gt;</BODY></MSG></NOTIFICATION>".toByteArray())
            snapshot.set(snapshot.get().copy(contacts = listOf(group.copy(nickname = "Renamed", members = listOf(GroupMember(me))))))
            repeat(5) { transport.incoming.send(notice) }
            withTimeout(3000) { client.contacts.first { it.single().displayName == "Renamed" } }
            assertEquals(2, fetches.get())
            assertEquals(listOf(me), client.contacts.value.single().members.map(GroupMember::email))

            // Another session leaves/deletes the group: NOT invalidates our address book.
            snapshot.set(snapshot.get().copy(contacts = emptyList()))
            transport.incoming.send(notice)
            withTimeout(3000) { client.contacts.first(List<Contact>::isEmpty) }
            assertTrue(client.sendMessageWithResult(groupId, "Removed", null).isFailure)
            assertTrue(transport.sent.none { it.verb == "XFR" })

            // A newly accepted group is fetched and subscribed without another login.
            snapshot.set(snapshot.get().copy(contacts = listOf(group.copy(id = otherGroupId, email = otherGroupId))))
            transport.incoming.send(notice)
            withTimeout(3000) { client.contacts.first { it.singleOrNull()?.id == otherGroupId } }
            withTimeout(3000) { while (transport.sent.none { it.verb == "ADL" && it.payload?.toString(Charsets.UTF_8)?.contains(otherGroupId.substringBefore('@')) == true }) delay(10) }
            snapshot.set(snapshot.get().copy(contacts = emptyList()))
            transport.incoming.send(MsnpCommand("RML", 0, payload = "<ml/>".toByteArray()))
            withTimeout(3000) { client.contacts.first(List<Contact>::isEmpty) }
            Unit
        } finally { client.close(); scope.cancel() }
    }

    @Test fun groupFeaturesFollowCurrentMembersAndClientChangesAndGuardEverySend() = runBlocking {
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = { error("Feature checks and group announcements do not need a switchboard") },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(group.copy(members = listOf(
                GroupMember(me), GroupMember("alice@example.com"), GroupMember("bob@example.com"),
            ))), circleTicket = "signed") },
            fetchStorageProfile = { _, _ -> StorageProfile() })
        val drawing = InkDrawing(canvasWidthPx = 100f, canvasHeightPx = 50f,
            strokes = listOf(InkStroke(listOf(InkPoint(1f, 2f), InkPoint(30f, 40f)))))
        val bytes = javaClass.getResourceAsStream("/winks/screen-punch.cab")!!.use { it.readBytes() }
        val wink = org.aralozkaya.msngr.protocol.WinkReference(
            org.aralozkaya.msngr.protocol.MsnDisplayPicture.create(me, bytes, 8), "Screen Punch")
        suspend fun awaitFeatures(ink: GroupInkFormat?, winks: Boolean) = withTimeout(2_000) {
            client.contacts.first { it.single().groupFeatures(me).let { f -> f.inkFormat == ink && f.winks == winks } }
        }
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(
                ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            transport.incoming.send(roster(groupId, me, "alice@example.com"))
            withTimeout(2_000) { client.contacts.first { it.single().presence.isOnline } }
            assertTrue(client.sendInkMessage(groupId, drawing).isFailure)
            assertTrue(client.sendWink(groupId, wink, bytes).isFailure)
            assertTrue(transport.sent.none { it.verb == "SDG" })

            // ILN can include a network ID before the nickname. Self and offline Bob lack support.
            transport.incoming.send(MsnpCommand("ILN", parameters = listOf("AWY",
                "1:alice@example.com;via=9:$groupId", "1", "Alice", "2416214060:1024", "0")))
            awaitFeatures(GroupInkFormat.Isf, true)
            client.sendInkMessage(groupId, drawing).getOrThrow()
            client.sendWink(groupId, wink, bytes).getOrThrow()
            val offer = parseCircleEnvelope(transport.sent.last { it.verb == "SDG" }.payload!!)!!
            assertEquals("Wink", offer.headers["message-type"])
            assertTrue(offer.body.toString(Charsets.UTF_8).contains("ID: 2\r\nData: <msnobj"))
            assertEquals(1, client.contacts.value.size) // Circle presence never adds a direct contact.

            transport.incoming.send(memberPresence("bob@example.com", "0:0", "BSY"))
            awaitFeatures(null, false)
            val submitted = transport.sent.count { it.verb == "SDG" }
            assertTrue(client.sendInkMessage(groupId, drawing).isFailure)
            assertTrue(client.sendWink(groupId, wink, bytes).isFailure)
            assertEquals(submitted, transport.sent.count { it.verb == "SDG" })

            // Each feature has its own gate; GIF-only clients select the compatible wire format.
            transport.incoming.send(memberPresence("bob@example.com", "4:0"))
            awaitFeatures(GroupInkFormat.Gif, false)
            client.sendInkMessage(groupId, drawing).getOrThrow()
            val ink = parseCircleEnvelope(transport.sent.last { it.verb == "SDG" }.payload!!)!!
            assertEquals(Msnp18Protocol.INK_GIF_CONTENT_TYPE, ink.headers["content-type"])
            assertNotNull(MsnInkCodec.decodeMimeBody(ink.headers["content-type"]!!, ink.body))
            transport.incoming.send(MsnpCommand("FLN", parameters = listOf("1:bob@example.com;via=9:$groupId")))
            awaitFeatures(GroupInkFormat.Isf, true)

            // Leaving and returning without a fresh capability announcement cannot reuse old flags.
            transport.incoming.send(roster(groupId, me))
            withTimeout(2_000) { client.contacts.first { !it.single().presence.isOnline } }
            transport.incoming.send(roster(groupId, me, "alice@example.com"))
            withTimeout(2_000) { client.contacts.first { it.single().presence.isOnline } }
            awaitFeatures(null, false)
            transport.incoming.send(memberPresence("alice@example.com", "32768:0"))
            awaitFeatures(null, true)
            transport.rejectSdg = true
            client.sendWink(groupId, wink, bytes)
            withTimeout(2_000) { client.messages.first { it[groupId]?.last()?.deliveryError != null } }
            Unit
        } finally { client.close(); scope.cancel() }
    }

    @Test fun groupWinkDownloadRetriesAndCachedOffersStayInTheirOwnConversations() = runBlocking {
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val directory = Files.createTempDirectory("group-wink").toFile()
        val store = WinkStore(directory)
        val bytes = javaClass.getResourceAsStream("/winks/screen-punch.cab")!!.use { it.readBytes() }
        val sender = "alice@example.com"
        val rejectFirstHandshake = AtomicBoolean(true)
        val boards = CopyOnWriteArrayList<WinkPeerTransport>()
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = {
                WinkPeerTransport(sender, me, bytes, rejectFirstHandshake.getAndSet(false)).also { boards += it }
            },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(group, group.copy(id = otherGroupId, email = otherGroupId)), circleTicket = "signed") },
            fetchStorageProfile = { _, _ -> StorageProfile() }, winkStore = store)
        val metadata = MsnDisplayPicture.create(sender, bytes, 8,
            friendly = org.aralozkaya.msngr.protocol.MsnBase64.encode("Screen Punch\u0000".toByteArray(Charsets.UTF_16LE)))
        suspend fun offer(id: String, offered: MsnDisplayPicture = metadata) {
            MsnpCircles.winkMessages(id, sender, "remote", offered) { 0 }.forEach { transport.incoming.send(it) }
        }
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(
                ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            transport.incoming.send(roster(groupId, me, sender))
            transport.incoming.send(roster(otherGroupId, me, sender))
            withTimeout(2_000) { client.contacts.first { it.all { c -> c.presence.isOnline } } }
            offer(groupId)
            val failed = withTimeout(2_000) {
                client.messages.first { it[groupId]?.singleOrNull()?.winkTransferError != null }
            }.getValue(groupId).single()
            assertEquals(sender, failed.senderEmail)
            client.retryMessage(failed).getOrThrow()
            val downloaded = withTimeout(5_000) {
                client.messages.first { it[groupId]?.singleOrNull()?.let { m -> !m.isWinkPending && m.winkTransferError == null } == true }
            }.getValue(groupId).single()
            assertTrue(downloaded.autoPlayWink)
            assertArrayEquals(bytes, store.bytes(AccountIdentity.forLogin(me).storageId, metadata))
            assertTrue(client.messages.value[sender].isNullOrEmpty())
            assertEquals(2, boards.size)
            assertTrue(boards.last().sent.any { it.verb == "MSG" })
            val transfers = transport.sent.count { it.verb == "XFR" }
            offer(otherGroupId)
            withTimeout(2_000) { client.messages.first { it[otherGroupId]?.singleOrNull()?.autoPlayWink == true } }
            assertEquals(transfers, transport.sent.count { it.verb == "XFR" })
            assertEquals("Screen Punch", client.messages.value.getValue(otherGroupId).single().wink?.name)
            offer(otherGroupId, metadata.copy(creator = "forged@example.com"))
            transport.incoming.send(memberPresence(sender)) // Barrier after the rejected offer.
            withTimeout(2_000) { client.contacts.first { it.first().members.any { m -> m.clientCapabilities != 0L } } }
            assertEquals(1, client.messages.value.getValue(otherGroupId).size)
            assertTrue(client.contacts.value.all { it.isGroup })
            Unit
        } finally { client.close(); scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun groupWinkPackageIsServedOnlyToItsOnlineRecipientsWithoutAddingPrivateContacts() = runBlocking {
        val transport = CircleTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bytes = javaClass.getResourceAsStream("/winks/screen-punch.cab")!!.use { it.readBytes() }
        val boards = java.util.concurrent.ConcurrentLinkedQueue<WinkRecipientTransport>()
        val client = LiveMsnp18Client(scope = scope, transport = transport,
            switchboardTransportFactory = { boards.remove() },
            authenticateSso = { _, _, _, _, _ -> SsoToken("ticket", "response", "contacts", "storage") },
            fetchAddressBook = { _, _, _ -> AddressBookSnapshot(listOf(group.copy(members = listOf(
                GroupMember(me), GroupMember("alice@example.com"), GroupMember("bob@example.com"),
            ))), circleTicket = "signed") }, fetchStorageProfile = { _, _ -> StorageProfile() })
        suspend fun connect(peer: String): WinkRecipientTransport {
            val board = WinkRecipientTransport(peer, me)
            boards.add(board)
            transport.incoming.send(MsnpCommand("RNG", parameters = listOf("session", "board.example:1864", "CKI", "key", peer, "Peer")))
            withTimeout(2_000) { while (board.sent.none { it.verb == "ANS" }) delay(10) }
            return board
        }
        try {
            client.signIn(SignInRequest(me, "password", ServerPresets.resolveForProvider(
                ProviderOptions.ESCARGOT, ServerOverrides()))).getOrThrow()
            transport.incoming.send(memberPresence("alice@example.com"))
            withTimeout(2_000) { client.contacts.first { it.single().groupFeatures(me).winks } }
            client.sendWink(groupId, org.aralozkaya.msngr.protocol.WinkReference(MsnDisplayPicture.create(me, bytes, 8)), bytes).getOrThrow()
            val metadata = client.messages.value.getValue(groupId).single().wink!!.metadata
            val alice = connect("alice@example.com")
            alice.request(metadata)
            withTimeout(5_000) { while (alice.received.get() == null) delay(10) }
            assertArrayEquals(bytes, alice.received.get())
            assertTrue(client.contacts.value.all { it.isGroup })
            assertTrue(client.messages.value["alice@example.com"].isNullOrEmpty())
            val bob = connect("bob@example.com") // Bob was offline when this package was announced.
            bob.request(metadata)
            withTimeout(2_000) {
                while (bob.sent.none { it.payload?.toString(Charsets.ISO_8859_1)?.contains("603 Decline") == true }) delay(10)
            }
            assertNull(bob.received.get())
            Unit
        } finally { client.close(); scope.cancel() }
    }

    private fun memberPresence(email: String, capabilities: String = "2416214060:1024", status: String = "NLN") =
        MsnpCommand("NLN", parameters = listOf(status, "1:$email;via=9:$groupId",
            email.substringBefore('@').replaceFirstChar { it.titlecase() }, capabilities, "0"))

    private fun roster(id: String, vararg emails: String): MsnpCommand {
        val body = "<circle><roster><id>IM</id>" + emails.joinToString("") { "<user><id>1:$it</id></user>" } + "</roster></circle>"
        return MsnpCommand("NFY", parameters = listOf("PUT"), payload =
            "Routing: 1.0\r\nFrom: 9:$id\r\n\r\nReliability: 1.0\r\n\r\nPublication: 1.0\r\nUri: /circle\r\n\r\n$body".toByteArray())
    }
}

private class CircleTransport : MsnpTransport {
    var incoming = Channel<MsnpCommand>(Channel.UNLIMITED)
    val sent = CopyOnWriteArrayList<MsnpCommand>()
    var rejectSdg = false
    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) { incoming = Channel(Channel.UNLIMITED) }
    override suspend fun send(command: MsnpCommand) {
        sent += command
        val params = when (command.verb) {
            "VER" -> listOf("MSNP18")
            "CVR" -> listOf("14.0.8117.0416")
            "USR" -> if (command.parameters.getOrNull(1) == "I") listOf("SSO", "S", "policy", "nonce") else listOf("OK")
            "ADL", "RML" -> listOf("OK")
            "XFR" -> listOf("SB", "board.example:1864", "CKI", "key")
            else -> null
        }
        if (params != null) incoming.send(MsnpCommand(command.verb, command.transactionId, params))
        if (command.verb == "SDG" && rejectSdg) incoming.send(MsnpCommand("933", command.transactionId))
    }
    override suspend fun receive() = incoming.receive()
    override fun close() { incoming.close() }
}

/** Real MSNObject P2P packets inside an in-memory switchboard, without private wink offers. */
private class WinkPeerTransport(
    private val peer: String,
    private val localEmail: String,
    bytes: ByteArray,
    private val rejectHandshake: Boolean,
) : MsnpTransport {
    private val incoming = Channel<MsnpCommand>(Channel.UNLIMITED)
    val sent = CopyOnWriteArrayList<MsnpCommand>()
    private val metadata = MsnDisplayPicture.create(peer, bytes, 8)
    private val remote = MsnpP2pSession(peer, localEmail,
        sendPacket = { packet -> incoming.send(MsnpCommand("MSG", parameters = listOf(peer, "Alice"),
            payload = Msnp18Protocol.p2pPayload(localEmail, packet, peer))) },
        localPicture = { null }, onDisplayPicture = {},
        localWink = { hash -> if (hash == metadata.sha1d) LocalDisplayPicture(metadata, bytes) else null })
    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) = Unit
    override suspend fun send(command: MsnpCommand) {
        sent += command
        when (command.verb) {
            "USR" -> incoming.send(MsnpCommand(if (rejectHandshake) "911" else "USR", command.transactionId, listOf("OK")))
            "CAL" -> {
                incoming.send(MsnpCommand("CAL", command.transactionId, listOf("RINGING", "session")))
                incoming.send(MsnpCommand("JOI", parameters = listOf(command.parameters.first(), "Peer")))
            }
            "MSG" -> {
                incoming.send(MsnpCommand("ACK", command.transactionId))
                val payload = parseSwitchboardMime(command.payload!!) as SwitchboardMimeMessage.P2p
                remote.handle(payload.bytes)
            }
        }
    }
    override suspend fun receive(): MsnpCommand = incoming.receive()
    override fun close() { incoming.close() }
}

private class WinkRecipientTransport(private val peer: String, private val localEmail: String) : MsnpTransport {
    private val incoming = Channel<MsnpCommand>(Channel.UNLIMITED)
    val sent = CopyOnWriteArrayList<MsnpCommand>()
    val received = AtomicReference<ByteArray?>()
    private val requester = MsnpP2pSession(peer, localEmail,
        sendPacket = { packet -> incoming.send(MsnpCommand("MSG", parameters = listOf(peer, "Peer"),
            payload = Msnp18Protocol.p2pPayload(localEmail, packet, peer))) },
        localPicture = { null }, onDisplayPicture = {}, onWink = { _, data -> received.set(data) })
    suspend fun request(metadata: MsnDisplayPicture) = requester.requestDisplayPicture(metadata)
    override suspend fun connect(host: String, port: Int, timeoutMillis: Int) = Unit
    override suspend fun send(command: MsnpCommand) {
        sent += command
        when (command.verb) {
            "ANS" -> incoming.send(MsnpCommand("ANS", command.transactionId, listOf("OK")))
            "MSG" -> {
                incoming.send(MsnpCommand("ACK", command.transactionId))
                requester.handle((parseSwitchboardMime(command.payload!!) as SwitchboardMimeMessage.P2p).bytes)
            }
        }
    }
    override suspend fun receive(): MsnpCommand = incoming.receive()
    override fun close() { incoming.close() }
}
