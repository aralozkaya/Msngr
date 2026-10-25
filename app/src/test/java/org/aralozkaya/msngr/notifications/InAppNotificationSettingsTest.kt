package org.aralozkaya.msngr.notifications

import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppNotificationSettingsTest {
    @Test fun defaultsEnableEveryTypeWithBannersAndVibration() {
        val settings = InAppNotificationSettings()
        InAppNotificationType.entries.forEach { type ->
            assertTrue(settings.allows(type))
            assertTrue(settings.vibrates(type))
            assertTrue(settings.showsBanner(type))
        }
    }

    @Test fun customSettingsTakePriorityOverGeneralSettings() {
        val custom = ConversationNotificationOverride("peer", "Peer", false, InAppNotificationOptions())
        val group = custom.copy(contactId = "group", displayName = "Group", isGroup = true)
        val settings = InAppNotificationSettings(
            defaults = InAppNotificationOptions(enabled = false, vibrate = false, banners = false,
                rules = InAppNotificationType.entries.associateWith { InAppNotificationRule(enabled = false, vibrate = false) }),
            conversations = mapOf("peer" to custom, "group" to group),
        )
        listOf("PEER", "group").forEach { id ->
            InAppNotificationType.entries.forEach { type ->
                assertTrue(settings.allows(type, id))
                assertTrue(settings.vibrates(type, id))
                assertTrue(settings.showsBanner(type, id))
                assertFalse(settings.allows(type, "other"))
                assertFalse(settings.vibrates(type, "other"))
                assertFalse(settings.showsBanner(type, "other"))
            }
        }
        val customMuted = settings.copy(defaults = InAppNotificationOptions(),
            conversations = mapOf("peer" to custom.copy(options = custom.options.copy(enabled = false))))
        assertFalse(customMuted.allows(InAppNotificationType.Message, "peer"))
        assertFalse(customMuted.vibrates(InAppNotificationType.Message, "peer"))
        assertFalse(customMuted.showsBanner(InAppNotificationType.Message, "peer"))
    }

    @Test fun categoryAndConversationRulesAreIndependent() {
        val defaults = InAppNotificationOptions(rules = mapOf(InAppNotificationType.Nudge to InAppNotificationRule(enabled = false)))
        val custom = ConversationNotificationOverride("peer", "Peer", false,
            InAppNotificationOptions(rules = mapOf(InAppNotificationType.Message to InAppNotificationRule(vibrate = false))))
        val settings = InAppNotificationSettings(defaults, conversations = mapOf("peer" to custom))
        assertFalse(settings.allows(InAppNotificationType.Nudge))
        assertTrue(settings.allows(InAppNotificationType.Message))
        assertTrue(settings.allows(InAppNotificationType.Nudge, "peer"))
        assertTrue(settings.showsBanner(InAppNotificationType.Message, "peer"))
        assertFalse(settings.vibrates(InAppNotificationType.Message, "peer"))
        assertFalse(settings.copy(conversations = mapOf("peer" to custom.copy(options = custom.options.copy(enabled = false))))
            .allows(InAppNotificationType.Message, "peer"))
    }

    @Test fun backBadgeCountsOtherUnreadConversations() {
        fun contact(id: String, count: Int) = Contact(id, id, id, Presence.Online, unreadCount = count)
        val contacts = listOf(contact("current", 5), contact("peer", 3), contact("group", 2).copy(isGroup = true),
            contact("blocked", 4).copy(isBlocked = true), contact("marked", 0).copy(isMarkedUnread = true))
        assertEquals(6, otherUnreadCount(contacts, "CURRENT"))
    }
}
