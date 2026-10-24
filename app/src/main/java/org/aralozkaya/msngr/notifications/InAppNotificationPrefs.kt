package org.aralozkaya.msngr.notifications

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.aralozkaya.msngr.AccountPreferences
import org.json.JSONArray
import org.json.JSONObject

internal object InAppNotificationPrefs {
    private const val NAME = "msngr_in_app_notifications"
    private val mutableState = MutableStateFlow(InAppNotificationSettings())

    @Synchronized
    fun state(context: Context): StateFlow<InAppNotificationSettings> {
        reload(context)
        return mutableState
    }

    @Synchronized
    fun read(context: Context): InAppNotificationSettings {
        reload(context)
        return mutableState.value
    }

    @Synchronized
    fun reload(context: Context) {
        val json = runCatching {
            JSONObject(AccountPreferences.preferences(context, NAME).getString("settings", "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val overrides = json.optJSONArray("conversations") ?: JSONArray()
        mutableState.value = InAppNotificationSettings(
            defaults = decodeOptions(json.optJSONObject("defaults")),
            conversations = buildMap {
                for (index in 0 until overrides.length()) {
                    val item = overrides.optJSONObject(index) ?: continue
                    val id = item.optString("id").notificationKey().takeIf { it.isNotBlank() } ?: continue
                    put(id, ConversationNotificationOverride(id, item.optString("name", id),
                        item.optBoolean("group"), decodeOptions(item.optJSONObject("options")),
                        item.optBoolean("backgroundEnabled", true), item.optBoolean("backgroundSilent", false)))
                }
            },
        )
    }

    @Synchronized
    fun update(context: Context, transform: (InAppNotificationSettings) -> InAppNotificationSettings) {
        val settings = transform(read(context))
        val json = JSONObject().put("defaults", encodeOptions(settings.defaults))
            .put("conversations", JSONArray().also { array ->
                settings.conversations.values.forEach { custom ->
                    array.put(JSONObject().put("id", custom.contactId).put("name", custom.displayName)
                        .put("group", custom.isGroup).put("options", encodeOptions(custom.options))
                        .put("backgroundEnabled", custom.backgroundEnabled).put("backgroundSilent", custom.backgroundSilent))
                }
            })
        AccountPreferences.preferences(context, NAME).edit { putString("settings", json.toString()) }
        mutableState.value = settings
    }

    fun customize(context: Context, id: String, name: String, isGroup: Boolean,
                  transform: (InAppNotificationOptions) -> InAppNotificationOptions) = update(context) { settings ->
        val key = id.notificationKey()
        val custom = settings.conversations[key] ?: ConversationNotificationOverride(key, name, isGroup, settings.defaults)
        settings.copy(conversations = settings.conversations +
            (key to custom.copy(displayName = name, isGroup = isGroup, options = transform(custom.options))))
    }

    fun setCustomizationEnabled(context: Context, id: String, name: String, isGroup: Boolean, enabled: Boolean) = update(context) { settings ->
        val key = id.notificationKey()
        settings.copy(conversations = if (enabled) {
            settings.conversations + (key to ConversationNotificationOverride(key, name, isGroup, settings.defaults))
        } else settings.conversations - key)
    }

    fun customizeBackground(context: Context, id: String, name: String, isGroup: Boolean,
                            transform: (ConversationNotificationOverride) -> ConversationNotificationOverride) = update(context) { settings ->
        val key = id.notificationKey()
        val custom = settings.conversations[key] ?: ConversationNotificationOverride(key, name, isGroup, settings.defaults)
        settings.copy(conversations = settings.conversations + (key to transform(custom)))
    }

    fun reset(context: Context, id: String) = update(context) {
        it.copy(conversations = it.conversations - id.notificationKey())
    }

    private fun decodeOptions(json: JSONObject?): InAppNotificationOptions = InAppNotificationOptions(
        enabled = json?.optBoolean("enabled", true) ?: true,
        vibrate = json?.optBoolean("vibrate", true) ?: true,
        banners = json?.optBoolean("banners", true) ?: true,
        rules = InAppNotificationType.entries.associateWith { type ->
            val rule = json?.optJSONObject("types")?.optJSONObject(type.name)
            InAppNotificationRule(rule?.optBoolean("enabled", true) ?: true,
                rule?.optBoolean("vibrate", true) ?: true)
        },
    )

    private fun encodeOptions(options: InAppNotificationOptions): JSONObject = JSONObject()
        .put("enabled", options.enabled).put("vibrate", options.vibrate).put("banners", options.banners)
        .put("types", JSONObject().also { types ->
            InAppNotificationType.entries.forEach { type ->
                val rule = options.rule(type)
                types.put(type.name, JSONObject().put("enabled", rule.enabled).put("vibrate", rule.vibrate))
            }
        })
}
