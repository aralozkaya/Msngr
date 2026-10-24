@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.ConversationNotificationSettingsActivity
import org.aralozkaya.msngr.appContainer
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.notifications.InAppNotificationOptions
import org.aralozkaya.msngr.notifications.InAppNotificationPrefs
import org.aralozkaya.msngr.notifications.InAppNotificationType
import org.aralozkaya.msngr.notifications.notificationKey
import org.aralozkaya.msngr.pages.components.PageContentList

@Composable
internal fun InAppNotificationSettingsScreen(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    onCustomizedNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val settings by remember(context) { InAppNotificationPrefs.state(context) }.collectAsState()
    PageContentList(innerPadding = innerPadding, scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        item(key = "customized-notifications") {
            FilledTonalButton(
                onClick = onCustomizedNotifications,
                modifier = Modifier.fillMaxWidth().testTag("customized_notification_settings"),
                shapes = ButtonDefaults.shapes(),
            ) { Text("Customized notifications") }
        }
        notificationToggle("In-app notifications", settings.defaults.enabled, true, "in_app_enabled") { enabled ->
            InAppNotificationPrefs.update(context) { it.copy(defaults = it.defaults.copy(enabled = enabled)) }
        }
        notificationOptions(settings.defaults, settings.defaults.enabled, InAppNotificationType.entries,
            onChange = { options -> InAppNotificationPrefs.update(context) { it.copy(defaults = options) } })
    }
}

@Composable
internal fun CustomizedNotificationsScreen(innerPadding: PaddingValues, scrollBehavior: TopAppBarScrollBehavior) {
    val context = LocalContext.current
    val settings by remember(context) { InAppNotificationPrefs.state(context) }.collectAsState()
    val repository = runCatching { context.appContainer.messengerRepository }.getOrNull()
    val contacts = repository?.contacts?.collectAsState()?.value.orEmpty()
    val customizations = settings.conversations.values.sortedBy { it.displayName.lowercase() }
    if (customizations.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("No custom notifications", textAlign = TextAlign.Center,
                modifier = Modifier.testTag("no_notification_customizations"))
        }
        return
    }
    PageContentList(innerPadding = innerPadding, scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        items(customizations, key = { it.contactId }) { custom ->
            val name = contacts.firstOrNull { it.id.notificationKey() == custom.contactId }?.displayName
                ?.messengerPlusPlainText() ?: custom.displayName
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(0, 1),
                onClick = { context.startActivity(ConversationNotificationSettingsActivity.createIntent(
                    context, custom.contactId, name, custom.isGroup)) },
                modifier = Modifier.fillMaxWidth().testTag("notification_custom_${custom.contactId}"),
                supportingContent = { Text((if (custom.isGroup) "Group" else "Contact") + " · " +
                    if (!custom.options.enabled && !custom.backgroundEnabled) "Muted" else "Custom alerts") },
                trailingContent = {
                    IconButton(onClick = { InAppNotificationPrefs.reset(context, custom.contactId) },
                        modifier = Modifier.testTag("delete_notification_custom_${custom.contactId}")) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Reset notifications for $name")
                    }
                },
                content = { Text(name) },
            )
        }
    }
}

@Composable
internal fun ConversationNotificationSettingsScreen(
    contactId: String, displayName: String, isGroup: Boolean,
    innerPadding: PaddingValues, scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val settings by remember(context) { InAppNotificationPrefs.state(context) }.collectAsState()
    val custom = settings.conversations[contactId.notificationKey()]
    val options = custom?.options ?: settings.defaults
    fun update(transform: (InAppNotificationOptions) -> InAppNotificationOptions) {
        InAppNotificationPrefs.customize(context, contactId, displayName, isGroup, transform)
    }
    PageContentList(innerPadding = innerPadding, scrollBehavior = scrollBehavior,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(displayName, style = MaterialTheme.typography.titleLarge)
            }
        }
        notificationToggle("Use custom settings", custom != null, true, "use_custom_notifications") { enabled ->
            InAppNotificationPrefs.setCustomizationEnabled(context, contactId, displayName, isGroup, enabled)
        }
        item { Text("In-app notifications", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
        notificationToggle("In-app alerts for this ${if (isGroup) "group" else "contact"}", options.enabled,
            custom != null, "conversation_notifications_enabled") { enabled ->
            update { it.copy(enabled = enabled) }
        }
        notificationOptions(options, custom != null && options.enabled,
            types = if (isGroup) listOf(InAppNotificationType.GroupMessage, InAppNotificationType.Nudge) else
                listOf(InAppNotificationType.Message, InAppNotificationType.Nudge, InAppNotificationType.ContactSignedIn),
            onChange = { changed -> update { changed } })
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Background notifications", style = MaterialTheme.typography.titleSmall)
                Text("Android notification permissions and channels also apply when Msngr is in the background.")
            }
        }
        notificationToggle("Background alerts", custom?.backgroundEnabled ?: true, custom != null, "conversation_background_enabled") { enabled ->
            InAppNotificationPrefs.customizeBackground(context, contactId, displayName, isGroup) { it.copy(backgroundEnabled = enabled) }
        }
        notificationToggle("Silent background alerts", custom?.backgroundSilent ?: false,
            custom != null && custom.backgroundEnabled, "conversation_background_silent") { silent ->
            InAppNotificationPrefs.customizeBackground(context, contactId, displayName, isGroup) { it.copy(backgroundSilent = silent) }
        }
        if (custom != null) item {
            TextButton(onClick = { InAppNotificationPrefs.reset(context, contactId) },
                modifier = Modifier.testTag("reset_conversation_notifications")) { Text("Reset to general settings") }
        }
    }
}

private fun LazyListScope.notificationOptions(
    options: InAppNotificationOptions, enabled: Boolean, types: List<InAppNotificationType>,
    onChange: (InAppNotificationOptions) -> Unit,
) {
    notificationToggle("Vibration", options.vibrate, enabled, "in_app_vibration") {
        onChange(options.copy(vibrate = it))
    }
    notificationToggle("Banners", options.banners, enabled, "in_app_banners") {
        onChange(options.copy(banners = it))
    }
    types.forEach { type ->
        item(key = "notification-section-${type.name}") {
            Text(type.label, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
                .semantics { heading() },
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        val rule = options.rule(type)
        notificationToggle("Notifications", rule.enabled, enabled, "in_app_type_${type.name}", index = 0, count = 2) {
            onChange(options.copy(rules = options.rules + (type to rule.copy(enabled = it))))
        }
        notificationToggle("Vibration", rule.vibrate,
            enabled && options.vibrate && rule.enabled, "in_app_type_vibration_${type.name}",
            index = 1, count = 2) {
            onChange(options.copy(rules = options.rules + (type to rule.copy(vibrate = it))))
        }
    }
}

private fun LazyListScope.notificationToggle(label: String, checked: Boolean, enabled: Boolean, tag: String,
                                            index: Int = 0, count: Int = 1, onChange: (Boolean) -> Unit) {
    item(key = tag) {
        SegmentedListItem(checked = checked, onCheckedChange = onChange, enabled = enabled,
            shapes = ListItemDefaults.segmentedShapes(index, count),
            modifier = Modifier.fillMaxWidth().testTag(tag),
            trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
            content = { Text(label) })
    }
}
