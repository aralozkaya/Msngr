package org.aralozkaya.msngr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.data.Contact
import java.io.File
import java.security.MessageDigest

internal data class RecentChatShortcut(
    val contactId: String,
    val label: String,
)

internal data class RecentContactPhoto(
    val contactId: String,
    val label: String,
    val avatarUri: String?,
)

private data class ShortcutState(
    val account: String,
    val recentChats: List<RecentChatShortcut>,
)

internal fun updatedRecentChats(
    recentChats: List<RecentChatShortcut>,
    openedChat: RecentChatShortcut,
): List<RecentChatShortcut> = buildList {
    add(openedChat)
    recentChats.forEach { recent ->
        if (none { it.contactId.equals(recent.contactId, ignoreCase = true) }) add(recent)
    }
}.take(AppShortcuts.MAX_RECENT_CHATS)

internal object AppShortcuts {
    private const val ACTION_OPEN_RECENT_CHAT =
        "org.aralozkaya.msngr.action.OPEN_RECENT_CHAT"
    private const val PREFS_NAME = "app_shortcuts"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_RECENT_ID_PREFIX = "recent_id_"
    private const val KEY_RECENT_LABEL_PREFIX = "recent_label_"
    private const val ICON_DIRECTORY = "shortcut_icons_v2"
    private const val LEGACY_ICON_DIRECTORY = "shortcut_icons"
    private const val STATIC_SHORTCUT_COUNT = 1
    private const val STATUS_SHORTCUT_ID = "change_status"
    const val MAX_RECENT_CHATS = 3
    private var lastPublishedState: ShortcutState? = null

    @Synchronized
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        File(appContext.filesDir, LEGACY_ICON_DIRECTORY).deleteRecursively()
        publish(appContext, readState(appContext))
    }

    @Synchronized
    fun activateAccount(context: Context, accountEmail: String) {
        val appContext = context.applicationContext
        val account = accountEmail.trim().lowercase()
        if (account.isBlank()) return
        val stored = readState(appContext)
        val active = if (stored.account == account) {
            stored
        } else {
            iconDirectory(appContext).deleteRecursively()
            ShortcutState(account, emptyList()).also { writeState(appContext, it) }
        }
        if (active != lastPublishedState) {
            publish(appContext, active)
        }
    }

    suspend fun recordRecentChat(
        context: Context,
        accountEmail: String,
        contactId: String,
        displayName: String,
        avatarUri: String?,
    ) {
        val appContext = context.applicationContext
        val account = accountEmail.trim().lowercase()
        val normalizedContactId = contactId.trim()
        if (account.isBlank() || normalizedContactId.isBlank()) return
        val opened = RecentChatShortcut(
            contactId = normalizedContactId,
            label = displayName
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
                .ifBlank { normalizedContactId },
        )
        val icon = appContext.loadAvatarBitmap(avatarUri)?.let {
            appContext.shortcutAvatarBitmap(opened.label, it)
        }
        withContext(Dispatchers.IO) {
            writeIcon(iconFile(appContext, account, normalizedContactId), icon)
        }
        synchronized(this) {
            val stored = readState(appContext)
            val current = stored.recentChats.takeIf { stored.account == account }.orEmpty()
            val updated = updatedRecentChats(current, opened)
            val active = ShortcutState(account, updated)
            if (active != stored) writeState(appContext, active)
            current.filterNot { old -> updated.any { it.contactId.equals(old.contactId, true) } }
                .forEach { old -> iconFile(appContext, account, old.contactId).delete() }
            publish(appContext, active)
            reportUsed(appContext, chatShortcutId(normalizedContactId))
        }
    }

    @Synchronized
    fun clearRecentChats(context: Context) {
        val appContext = context.applicationContext
        preferences(appContext).edit { clear() }
        publish(appContext, ShortcutState("", emptyList()))
        iconDirectory(appContext).deleteRecursively()
        File(appContext.filesDir, LEGACY_ICON_DIRECTORY).deleteRecursively()
    }

    fun reportStatusUsed(context: Context) {
        reportUsed(context.applicationContext, STATUS_SHORTCUT_ID)
    }

    @Synchronized
    fun recentContactPhotos(
        context: Context,
        accountEmail: String,
        contacts: List<Contact>,
    ): List<RecentContactPhoto> {
        val state = readState(context.applicationContext)
        if (state.account != accountEmail.trim().lowercase()) return emptyList()
        return state.recentChats.mapNotNull { recent ->
            contacts.firstOrNull { it.id.equals(recent.contactId, ignoreCase = true) }?.let {
                RecentContactPhoto(recent.contactId, recent.label, it.avatarUri)
            }
        }
    }

    suspend fun syncContactPhotos(
        context: Context,
        accountEmail: String,
        photos: List<RecentContactPhoto>,
    ) {
        if (photos.isEmpty()) return
        val appContext = context.applicationContext
        val account = accountEmail.trim().lowercase()
        photos.forEach { photo ->
            val icon = appContext.loadAvatarBitmap(photo.avatarUri)?.let {
                appContext.shortcutAvatarBitmap(photo.label, it)
            }
            withContext(Dispatchers.IO) {
                writeIcon(iconFile(appContext, account, photo.contactId), icon)
            }
        }
        synchronized(this) {
            val state = readState(appContext)
            if (state.account == account && state.recentChats.isNotEmpty()) {
                publish(appContext, state)
            }
        }
    }

    private fun readState(context: Context): ShortcutState {
        val preferences = preferences(context)
        val recentChats = (0 until MAX_RECENT_CHATS).mapNotNull { index ->
            val contactId = preferences.getString("$KEY_RECENT_ID_PREFIX$index", null)
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            RecentChatShortcut(
                contactId = contactId,
                label = preferences.getString("$KEY_RECENT_LABEL_PREFIX$index", contactId)
                    .orEmpty()
                    .ifBlank { contactId },
            )
        }
        return ShortcutState(
            account = preferences.getString(KEY_ACCOUNT, "").orEmpty(),
            recentChats = recentChats,
        )
    }

    private fun writeState(context: Context, state: ShortcutState) {
        preferences(context).edit {
            clear()
            putString(KEY_ACCOUNT, state.account)
            state.recentChats.take(MAX_RECENT_CHATS).forEachIndexed { index, recent ->
                putString("$KEY_RECENT_ID_PREFIX$index", recent.contactId)
                putString("$KEY_RECENT_LABEL_PREFIX$index", recent.label)
            }
        }
    }

    private fun publish(context: Context, state: ShortcutState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val availableChatSlots = runCatching {
            (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) -
                STATIC_SHORTCUT_COUNT).coerceIn(0, MAX_RECENT_CHATS)
        }.getOrNull() ?: run {
            lastPublishedState = null
            return
        }
        val shortcuts = state.recentChats.take(availableChatSlots).mapIndexed { index, recent ->
            val bitmap = BitmapFactory.decodeFile(
                iconFile(context, state.account, recent.contactId).path,
            ) ?: context.shortcutAvatarBitmap(recent.label, null)
            ShortcutInfoCompat.Builder(context, chatShortcutId(recent.contactId))
                .setShortLabel(recent.label)
                .setLongLabel(context.getString(R.string.shortcut_chat_long, recent.label))
                .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
                .setRank(index)
                .setIntent(
                    LoginActivity.createRecentChatShortcutIntent(
                        context = context,
                        accountEmail = state.account,
                        contact = recent,
                        action = ACTION_OPEN_RECENT_CHAT,
                    ),
                )
                .build()
        }
        val published = runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }.getOrDefault(false)
        lastPublishedState = state.takeIf { published }
    }

    private fun reportUsed(context: Context, shortcutId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, shortcutId) }
    }

    private fun chatShortcutId(contactId: String): String = "recent_chat_" +
        Base64.encodeToString(
            contactId.lowercase().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
        )

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun iconDirectory(context: Context) = File(context.filesDir, ICON_DIRECTORY)

    private fun iconFile(context: Context, account: String, contactId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$account:$contactId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(iconDirectory(context), "$digest.png")
    }

    private fun writeIcon(file: File, icon: Bitmap?) {
        if (icon == null) {
            file.delete()
            return
        }
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        val temporary = File.createTempFile("shortcut_", ".png", file.parentFile)
        try {
            temporary.outputStream().use { output ->
                check(icon.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            check(temporary.renameTo(file))
        } finally {
            temporary.delete()
        }
    }
}
