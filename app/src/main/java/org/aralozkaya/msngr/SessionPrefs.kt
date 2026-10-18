package org.aralozkaya.msngr

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import org.aralozkaya.msngr.enums.Presence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/** Customizable endpoints used by the MSNP and companion HTTP services. */
data class ServerOverrides(
    val mainServer: String = "",
    val mainPort: Int = 1863,
    val switchboardPort: Int = 1864,
    val configServer: String = "",
    val authServerEnabled: Boolean = false,
    val authServer: String = "",
    val addressBookServerEnabled: Boolean = false,
    val addressBookServer: String = "",
    val adsServerEnabled: Boolean = false,
    val adsServer: String = "",
    val storageServerEnabled: Boolean = false,
    val storageServer: String = "",
    val socialServerEnabled: Boolean = false,
    val socialServer: String = "",
    val activitiesServerEnabled: Boolean = false,
    val activitiesServer: String = "",
    val mailServerEnabled: Boolean = false,
    val mailServer: String = "",
) {
    fun normalized(): ServerOverrides = copy(
        mainServer = mainServer.trim(),
        configServer = configServer.trim(),
        authServer = authServer.trim(),
        addressBookServer = addressBookServer.trim(),
        adsServer = adsServer.trim(),
        storageServer = storageServer.trim(),
        socialServer = socialServer.trim(),
        activitiesServer = activitiesServer.trim(),
        mailServer = mailServer.trim(),
    )

    fun hasRequiredServers(): Boolean =
        mainServer.trim().isNotEmpty() && configServer.trim().isNotEmpty()

    fun isValid(): Boolean {
        val value = normalized()
        return value.hasRequiredServers() &&
            value.mainPort in 1..65535 &&
            value.switchboardPort in 1..65535 &&
            (!value.authServerEnabled || value.authServer.isNotEmpty()) &&
            (!value.addressBookServerEnabled || value.addressBookServer.isNotEmpty()) &&
            (!value.adsServerEnabled || value.adsServer.isNotEmpty()) &&
            (!value.storageServerEnabled || value.storageServer.isNotEmpty()) &&
            (!value.socialServerEnabled || value.socialServer.isNotEmpty()) &&
            (!value.activitiesServerEnabled || value.activitiesServer.isNotEmpty()) &&
            (!value.mailServerEnabled || value.mailServer.isNotEmpty())
    }

    fun toResolved(): ServerConfig {
        val value = normalized()
        return ServerConfig(
            mainServer = value.mainServer,
            mainPort = value.mainPort,
            switchboardPort = value.switchboardPort,
            configServer = value.configServer,
            authServer = optional(value.authServerEnabled, value.authServer, value.mainServer),
            addressBookServer = optional(
                value.addressBookServerEnabled,
                value.addressBookServer,
                value.mainServer,
            ),
            adsServer = optional(value.adsServerEnabled, value.adsServer, value.mainServer),
            storageServer = optional(
                value.storageServerEnabled,
                value.storageServer,
                value.mainServer,
            ),
            socialServer = optional(value.socialServerEnabled, value.socialServer, value.mainServer),
            activitiesServer = optional(
                value.activitiesServerEnabled,
                value.activitiesServer,
                value.mainServer,
            ),
            mailServer = optional(value.mailServerEnabled, value.mailServer, value.mainServer),
        )
    }

    private fun optional(enabled: Boolean, value: String, fallback: String): String =
        if (enabled && value.isNotEmpty()) value else fallback
}

data class ServerConfig(
    val mainServer: String,
    val mainPort: Int,
    val switchboardPort: Int,
    val configServer: String,
    val authServer: String,
    val addressBookServer: String,
    val adsServer: String,
    val storageServer: String,
    val socialServer: String,
    val activitiesServer: String,
    val mailServer: String,
)

/** Each remembered account has its own encrypted credentials and service configuration. */
data class StoredAccountDto(
    val email: String = "",
    val password: String = "",
    val remember: Boolean = false,
    val provider: String = ProviderOptions.ESCARGOT,
    val customServers: ServerOverrides = ServerOverrides(),
    val useMockServer: Boolean = false,
    val automaticSignIn: Boolean = false,
    val loginPresence: Presence = Presence.Online,
)

data class LoginSnapshot(
    val email: String,
    val password: String,
    val savePassword: Boolean,
    val provider: String,
    val customServers: ServerOverrides,
    val resolvedServers: ServerConfig,
    val useMockServer: Boolean,
    val automaticSignIn: Boolean,
    val loginPresence: Presence,
)

data class UserProfileSnapshot(
    val username: String,
    val email: String,
    val presence: Presence,
    val avatarUri: String? = null,
    val statusMessage: String = "",
)

object ProviderOptions {
    const val ESCARGOT = "Escargot"
    const val CROSSTALK = "CrossTalk"
    const val CUSTOM = "Custom"
}

/** Presets are based on the current NINA guide and CrossTalk reroute sample configuration. */
object ServerPresets {
    val providerOptions = listOf(
        ProviderOptions.ESCARGOT,
        ProviderOptions.CROSSTALK,
        ProviderOptions.CUSTOM,
    )

    private val escargot = ServerOverrides(
        mainServer = "ds.escargot.nina.chat",
        configServer = "mconf.escargot.nina.chat",
    )

    private val crossTalk = ServerOverrides(
        mainServer = "ms.msgrsvcs.ctsrv.gay",
        configServer = "conf.msgrsvcs.ctsrv.gay",
        authServerEnabled = true,
        authServer = "ctas.login.ugnet.gay",
        addressBookServerEnabled = true,
        addressBookServer = "ctsvcs.addressbook.ugnet.gay",
        adsServerEnabled = true,
        adsServer = "ctsvcs.advertising.ugnet.gay",
        storageServerEnabled = true,
        storageServer = "cts.storage.ugnet.gay",
        socialServerEnabled = true,
        socialServer = "social.ugnet.gay",
        activitiesServerEnabled = true,
        activitiesServer = "mactivities.msgrsvcs.ctsrv.gay",
        mailServerEnabled = true,
        mailServer = "mail.ugnet.gay",
    )

    fun presetFor(provider: String): ServerOverrides = when (provider) {
        ProviderOptions.CROSSTALK -> crossTalk
        else -> escargot
    }

    fun initialCustomServers(
        selectedProvider: String,
        currentCustomServers: ServerOverrides,
    ): ServerOverrides =
        if (selectedProvider == ProviderOptions.CUSTOM && currentCustomServers.hasRequiredServers()) {
            currentCustomServers.normalized()
        } else {
            presetFor(selectedProvider)
        }

    fun resolveForProvider(provider: String, customServers: ServerOverrides): ServerConfig =
        when (provider) {
            ProviderOptions.CROSSTALK -> crossTalk
            ProviderOptions.CUSTOM -> customServers.takeIf(ServerOverrides::isValid) ?: escargot
            else -> escargot
        }.toResolved()
}

object SessionPrefs {
    private const val PROFILE_PREFS = "msngr_profile"
    private const val KEY_USERNAME = "username"
    private const val KEY_PRESENCE = "presence"
    private const val KEY_AVATAR_URI = "avatar_uri"
    private const val KEY_STATUS_MESSAGE = "status_message"
    private const val KEY_REMOTE_SIGN_OUT = "remote_sign_out"
    internal const val KEY_SIGN_OUT_OTHER_SESSIONS = "sign_out_other_sessions_on_sign_in"

    fun signsOutOtherSessionsOnSignIn(
        context: Context,
        account: AccountIdentity? = preferenceAccount(context),
    ): Boolean = account != null && AccountPreferences.forAccount(context, PROFILE_PREFS, account)
        .getBoolean(KEY_SIGN_OUT_OTHER_SESSIONS, false)

    fun setSignOutOtherSessionsOnSignIn(context: Context, enabled: Boolean) {
        val account = preferenceAccount(context) ?: return
        AccountPreferences.forAccount(context, PROFILE_PREFS, account).edit {
            putBoolean(KEY_SIGN_OUT_OTHER_SESSIONS, enabled)
        }
    }

    fun isLoginComplete(context: Context): Boolean = hasRememberedCredentials(context)

    fun hasRememberedCredentials(context: Context): Boolean {
        val stored = SecureAccountStore.read(context)
        return stored.remember && stored.email.isNotBlank() && stored.password.isNotEmpty()
    }

    /** Authenticated activities require a backend-validated session, not merely stored bytes. */
    fun canOpenAuthenticatedScreens(context: Context): Boolean = SessionRuntime.isActive()

    fun isMockServerActive(): Boolean = SessionRuntime.isUsingMockServer()

    fun readLoginSnapshot(context: Context): LoginSnapshot {
        return loginSnapshot(SessionRuntime.account() ?: SecureAccountStore.read(context))
    }

    /** Looks up the login form's account without borrowing another user's options. */
    fun readLoginSnapshot(context: Context, email: String): LoginSnapshot =
        loginSnapshot(accountForEmail(context, email)?.let { SecureAccountStore.read(context, it) } ?: StoredAccountDto())

    fun readLoginSnapshot(context: Context, account: AccountIdentity): LoginSnapshot =
        loginSnapshot(SecureAccountStore.read(context, account))

    fun rememberedAccounts(context: Context): List<String> =
        rememberedProfiles(context).map { it.email }.distinct()

    fun rememberedProfiles(context: Context): List<LoginSnapshot> =
        SecureAccountStore.accounts(context).map(::loginSnapshot).sortedBy { it.accountLabel }

    fun selectLoginAccount(context: Context, email: String) {
        selectLoginAccount(context, accountForEmail(context, email))
    }

    fun selectLoginAccount(context: Context, account: AccountIdentity?) {
        SecureAccountStore.select(context, account)
        AccountPreferences.reload(context)
    }

    internal fun preferenceAccount(context: Context): AccountIdentity? =
        (SessionRuntime.account() ?: SecureAccountStore.read(context))
            .takeIf { it.email.isNotBlank() }?.accountIdentity

    internal fun accountForEmail(context: Context, email: String): AccountIdentity? {
        val normalized = email.normalizedAccountEmail()
        if (normalized.isBlank()) return null
        preferenceAccount(context)?.takeIf { it.email == normalized }?.let { return it }
        return SecureAccountStore.accounts(context).singleOrNull { it.email == normalized }?.accountIdentity
    }

    private fun loginSnapshot(stored: StoredAccountDto): LoginSnapshot {
        val customServers = stored.customServers.normalized()
        val provider = normalizeProvider(stored.provider, customServers)
        return LoginSnapshot(
            email = stored.email,
            password = if (stored.remember) stored.password else "",
            savePassword = stored.remember,
            provider = provider,
            customServers = customServers,
            resolvedServers = ServerPresets.resolveForProvider(provider, customServers),
            useMockServer = stored.useMockServer,
            automaticSignIn = stored.remember && stored.automaticSignIn,
            loginPresence = stored.loginPresence,
        )
    }

    fun readServerConfig(context: Context): ServerConfig = readLoginSnapshot(context).resolvedServers

    fun saveServerSettings(
        context: Context,
        provider: String,
        customServers: ServerOverrides,
        email: String = preferenceAccount(context)?.email.orEmpty(),
        account: AccountIdentity? = accountForEmail(context, email),
    ) {
        val normalizedServers = customServers.normalized()
        updateAccount(context, account) { stored ->
            stored.copy(
                provider = normalizeProvider(provider, normalizedServers),
                customServers = normalizedServers,
            )
        }
    }

    fun completeLogin(
        context: Context,
        email: String,
        password: String,
        savePassword: Boolean,
        provider: String,
        customServers: ServerOverrides,
        useMockServer: Boolean,
        automaticSignIn: Boolean = false,
        loginPresence: Presence = Presence.Online,
    ) {
        val normalizedEmail = email.normalizedAccountEmail()
        val normalizedServers = customServers.normalized()
        val account = StoredAccountDto(
            email = normalizedEmail,
            password = if (savePassword) password else "",
            remember = savePassword,
            provider = normalizeProvider(provider, normalizedServers),
            customServers = normalizedServers,
            useMockServer = useMockServer,
            automaticSignIn = savePassword && automaticSignIn,
            loginPresence = loginPresence,
        )
        val identity = account.accountIdentity
        val existing = SecureAccountStore.read(context, identity)
        if (!savePassword || !existing.remember) AccountPreferences.clear(context, identity)
        if (savePassword) {
            SecureAccountStore.write(context, account)
            SecureAccountStore.select(context, identity)
        } else {
            SecureAccountStore.remove(context, identity)
            SecureAccountStore.select(context, null)
        }
        SessionRuntime.activate(context, account)
        AccountPreferences.forAccount(context, "msngr_profile", identity).edit {
            remove(KEY_REMOTE_SIGN_OUT)
        }
        AccountPreferences.reload(context)
    }

    /** Forgetting a login removes that account's saved profile and preferences immediately. */
    fun disableRememberedCredentials(
        context: Context,
        email: String = preferenceAccount(context)?.email.orEmpty(),
        account: AccountIdentity? = accountForEmail(context, email),
    ) {
        if (account == null) return
        SecureAccountStore.remove(context, account)
        AccountPreferences.clear(context, account)
        SessionRuntime.update(account) {
            it.copy(
                password = "",
                remember = false,
                automaticSignIn = false,
            )
        }
        AccountPreferences.reload(context)
    }

    fun setAutomaticSignIn(
        context: Context,
        enabled: Boolean,
        email: String = preferenceAccount(context)?.email.orEmpty(),
        account: AccountIdentity? = accountForEmail(context, email),
    ) {
        updateAccount(context, account) { it.copy(automaticSignIn = it.remember && enabled) }
    }

    fun setLoginPresence(
        context: Context,
        presence: Presence,
        email: String = preferenceAccount(context)?.email.orEmpty(),
        account: AccountIdentity? = accountForEmail(context, email),
    ) {
        updateAccount(context, account) { it.copy(loginPresence = presence) }
    }

    /** Called only after remembered credentials have been accepted by the backend. */
    fun activateRememberedSession(context: Context) {
        val stored = SecureAccountStore.read(context)
        check(stored.remember && stored.email.isNotBlank())
        SessionRuntime.activate(context, stored)
        AccountPreferences.reload(context)
    }

    /** Drops a failed password while retaining the remembered account and its preferences. */
    fun invalidateRememberedCredentials(context: Context) {
        val stored = SecureAccountStore.read(context)
        // Invalid credentials must not erase the user's profile edits or affect other accounts.
        if (stored.remember) {
            SecureAccountStore.write(context, stored.copy(password = "", automaticSignIn = false))
        }
        SessionRuntime.clear()
    }

    fun readUserProfile(context: Context): UserProfileSnapshot {
        val email = preferenceAccount(context)?.email.orEmpty()
        return mergeServerProfile(
            context = context,
            serverProfile = UserProfileSnapshot(
                username = email.substringBefore('@').ifBlank { "You" },
                email = email,
                presence = Presence.Online,
            ),
        )
    }

    /** Keeps explicit local edits while using freshly fetched server profile values as defaults. */
    fun mergeServerProfile(
        context: Context,
        serverProfile: UserProfileSnapshot,
        account: AccountIdentity? = accountForEmail(context, serverProfile.email),
    ): UserProfileSnapshot {
        if (account == null || account.email != serverProfile.email.normalizedAccountEmail()) return serverProfile
        val profilePrefs = AccountPreferences.forAccount(context, PROFILE_PREFS, account)
        val username = if (profilePrefs.contains(KEY_USERNAME)) {
            profilePrefs.getString(KEY_USERNAME, serverProfile.username).orEmpty().trim()
                .ifBlank { serverProfile.username }
        } else {
            serverProfile.username
        }
        val presence = if (profilePrefs.contains(KEY_PRESENCE)) {
            Presence.fromStorageValue(profilePrefs.getString(KEY_PRESENCE, "").orEmpty())
        } else {
            serverProfile.presence
        }
        val statusMessage = if (profilePrefs.contains(KEY_STATUS_MESSAGE)) {
            profilePrefs.getString(KEY_STATUS_MESSAGE, "").orEmpty()
                .replace('\r', ' ')
                .replace('\n', ' ')
        } else {
            serverProfile.statusMessage
        }
        val storedAvatar = profilePrefs.getString(KEY_AVATAR_URI, null)
        val localAvatar = storedAvatar?.takeUnless(String::isInlineDisplayPicture)
        if (storedAvatar != null && localAvatar == null) {
            profilePrefs.edit { remove(KEY_AVATAR_URI) }
        }
        return serverProfile.copy(
            username = username,
            presence = presence,
            avatarUri = localAvatar ?: serverProfile.avatarUri,
            statusMessage = statusMessage,
        )
    }

    fun saveUserProfile(
        context: Context,
        username: String,
        presence: Presence,
        avatarUri: String? = readUserProfile(context).avatarUri,
        statusMessage: String = readUserProfile(context).statusMessage,
    ) {
        if (preferenceAccount(context) == null) return
        AccountPreferences.preferences(context, PROFILE_PREFS).edit {
            putString(KEY_USERNAME, username.trim())
            putString(KEY_PRESENCE, presence.name)
            putString(
                KEY_STATUS_MESSAGE,
                statusMessage.replace('\r', ' ').replace('\n', ' ').trim(),
            )
            if (avatarUri.isNullOrBlank() || avatarUri.isInlineDisplayPicture()) {
                remove(KEY_AVATAR_URI)
            } else {
                putString(KEY_AVATAR_URI, avatarUri)
            }
        }
    }

    fun logout() {
        SessionRuntime.clear()
    }

    /** Keep queued notification work from recreating a session after it was revoked remotely. */
    internal fun recordRemoteSignOut(context: Context) {
        val account = preferenceAccount(context) ?: return
        AccountPreferences.forAccount(context, "msngr_profile", account).edit(commit = true) {
            putBoolean(KEY_REMOTE_SIGN_OUT, true)
        }
        setAutomaticSignIn(context, false, account = account)
    }

    internal fun wasRemotelySignedOut(context: Context, account: AccountIdentity? = preferenceAccount(context)): Boolean =
        account != null && AccountPreferences.forAccount(context, "msngr_profile", account)
            .getBoolean(KEY_REMOTE_SIGN_OUT, false)

    private fun updateAccount(
        context: Context,
        account: AccountIdentity?,
        transform: (StoredAccountDto) -> StoredAccountDto,
    ) {
        if (account == null) return
        val stored = SecureAccountStore.read(context, account)
        val updated = transform(stored)
        if (stored.remember && updated.accountIdentity == account) SecureAccountStore.write(context, updated)
        SessionRuntime.update(account) { current -> transform(current).takeIf { it.accountIdentity == account } ?: current }
    }

    private fun normalizeProvider(provider: String, customServers: ServerOverrides): String =
        when (provider) {
            ProviderOptions.ESCARGOT, ProviderOptions.CROSSTALK -> provider
            ProviderOptions.CUSTOM -> if (customServers.isValid()) provider else ProviderOptions.ESCARGOT
            else -> ProviderOptions.ESCARGOT
        }
}

private fun String.isInlineDisplayPicture(): Boolean =
    startsWith("data:image/", ignoreCase = true)

private object SessionRuntime {
    @Volatile private var activeAccount: StoredAccountDto? = null
    private var storageContext: Context? = null

    fun activate(context: Context, account: StoredAccountDto) {
        val previous = activeAccount
        if (previous != null && !previous.remember) {
            storageContext?.let { AccountPreferences.clear(it, previous.accountIdentity) }
        }
        storageContext = if (context.isDeviceProtectedStorage) {
            context.applicationContext.createDeviceProtectedStorageContext()
        } else {
            context.applicationContext
        }
        activeAccount = account
    }

    fun update(account: AccountIdentity, transform: (StoredAccountDto) -> StoredAccountDto) {
        activeAccount?.takeIf { it.accountIdentity == account }
            ?.let { activeAccount = transform(it) }
    }

    fun clear() {
        val previous = activeAccount
        activeAccount = null
        storageContext?.let { context ->
            if (previous != null && !previous.remember) {
                AccountPreferences.clear(context, previous.accountIdentity)
            }
            AccountPreferences.reload(context)
        }
        storageContext = null
    }

    fun account(): StoredAccountDto? = activeAccount
    fun isActive(): Boolean = activeAccount != null
    fun isUsingMockServer(): Boolean = activeAccount?.useMockServer == true
}

/** Binary codec avoids persisting a partly encrypted object graph or relying on reflection. */
internal object StoredAccountCodec {
    private const val VERSION = 4
    private const val MOCK_SERVER_VERSION = 3
    private const val LEGACY_VERSION = 2

    fun encode(value: StoredAccountDto): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeUTF(value.email)
            output.writeUTF(value.password)
            output.writeBoolean(value.remember)
            output.writeUTF(value.provider)
            with(value.customServers) {
                output.writeUTF(mainServer)
                output.writeInt(mainPort)
                output.writeInt(switchboardPort)
                output.writeUTF(configServer)
                output.writeOptional(authServerEnabled, authServer)
                output.writeOptional(addressBookServerEnabled, addressBookServer)
                output.writeOptional(adsServerEnabled, adsServer)
                output.writeOptional(storageServerEnabled, storageServer)
                output.writeOptional(socialServerEnabled, socialServer)
                output.writeOptional(activitiesServerEnabled, activitiesServer)
                output.writeOptional(mailServerEnabled, mailServer)
            }
            output.writeBoolean(value.useMockServer)
            output.writeBoolean(value.automaticSignIn)
            output.writeUTF(value.loginPresence.name)
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): StoredAccountDto = DataInputStream(ByteArrayInputStream(bytes)).use {
        val version = it.readInt()
        require(version in LEGACY_VERSION..VERSION) {
            "Unsupported stored account version"
        }
        val email = it.readUTF()
        val password = it.readUTF()
        val remember = it.readBoolean()
        val provider = it.readUTF()
        val mainServer = it.readUTF()
        val mainPort = it.readInt()
        val switchboardPort = it.readInt()
        val configServer = it.readUTF()
        val auth = it.readOptional()
        val addressBook = it.readOptional()
        val ads = it.readOptional()
        val storage = it.readOptional()
        val social = it.readOptional()
        val activities = it.readOptional()
        val mail = it.readOptional()
        StoredAccountDto(
            email = email,
            password = password,
            remember = remember,
            provider = provider,
            customServers = ServerOverrides(
                mainServer = mainServer,
                mainPort = mainPort,
                switchboardPort = switchboardPort,
                configServer = configServer,
                authServerEnabled = auth.first,
                authServer = auth.second,
                addressBookServerEnabled = addressBook.first,
                addressBookServer = addressBook.second,
                adsServerEnabled = ads.first,
                adsServer = ads.second,
                storageServerEnabled = storage.first,
                storageServer = storage.second,
                socialServerEnabled = social.first,
                socialServer = social.second,
                activitiesServerEnabled = activities.first,
                activitiesServer = activities.second,
                mailServerEnabled = mail.first,
                mailServer = mail.second,
            ),
            useMockServer = if (version >= MOCK_SERVER_VERSION) it.readBoolean() else false,
            automaticSignIn = if (version >= VERSION) it.readBoolean() else false,
            loginPresence = if (version >= VERSION) {
                Presence.fromStorageValue(it.readUTF())
            } else {
                Presence.Online
            },
        )
    }

    private fun DataOutputStream.writeOptional(enabled: Boolean, value: String) {
        writeBoolean(enabled)
        writeUTF(value)
    }

    private fun DataInputStream.readOptional(): Pair<Boolean, String> =
        readBoolean() to readUTF()
}

private object SecureAccountStore {
    private const val PREFS_NAME = "msngr_session_secure"
    private const val KEY_BLOB = "account_dto_v2"
    private const val KEY_ACCOUNT_PREFIX = "profile_"
    private const val LEGACY_ACCOUNT_PREFIX = "account_"
    private const val KEY_SELECTED_ACCOUNT = "selected_account"
    private const val KEY_MIGRATED = "service_accounts_migrated"
    private const val KEYSET_PREFS_NAME = "msngr_session_secure_keyset"
    private const val KEYSET_NAME = "msngr_session_secure_keyset"
    private const val MASTER_KEY_URI = "android-keystore://msngr_session_master_key"
    private val associatedData = "org.aralozkaya.msngr.StoredAccountDto.v2"
        .toByteArray(StandardCharsets.UTF_8)

    @Volatile private var cachedAead: Aead? = null

    @Synchronized
    fun read(context: Context): StoredAccountDto {
        val prefs = preferences(context)
        val key = prefs.getString(KEY_SELECTED_ACCOUNT, null) ?: return StoredAccountDto()
        return readAccount(context, prefs, key)
    }

    @Synchronized
    fun read(context: Context, account: AccountIdentity): StoredAccountDto {
        val prefs = preferences(context)
        return readAccount(context, prefs, account.storageKey)
    }

    @Synchronized
    fun accounts(context: Context): List<StoredAccountDto> {
        val prefs = preferences(context)
        return prefs.all.keys.filter { it.startsWith(KEY_ACCOUNT_PREFIX) }
            .map { readAccount(context, prefs, it.removePrefix(KEY_ACCOUNT_PREFIX)) }
            .filter { it.remember && it.email.isNotBlank() }
    }

    @Synchronized
    fun select(context: Context, account: AccountIdentity?) {
        val prefs = preferences(context)
        val key = account?.storageKey
        prefs.edit {
            if (key != null && prefs.contains(KEY_ACCOUNT_PREFIX + key)) {
                putString(KEY_SELECTED_ACCOUNT, key)
            } else {
                remove(KEY_SELECTED_ACCOUNT)
            }
        }
    }

    @Synchronized
    fun remove(context: Context, account: AccountIdentity) {
        val prefs = preferences(context)
        val key = account.storageKey
        prefs.edit {
            remove(KEY_ACCOUNT_PREFIX + key)
            if (prefs.getString(KEY_SELECTED_ACCOUNT, null) == key) remove(KEY_SELECTED_ACCOUNT)
        }
    }

    private fun readAccount(
        context: Context,
        prefs: SharedPreferences,
        key: String,
    ): StoredAccountDto {
        val encoded = prefs.getString(KEY_ACCOUNT_PREFIX + key, null) ?: return StoredAccountDto()
        return try {
            val encrypted = Base64.decode(encoded, Base64.DEFAULT)
            StoredAccountCodec.decode(aead(context).decrypt(encrypted, accountAssociatedData(key)))
                .also { require(it.accountIdentity.storageKey == key) }
        } catch (_: Exception) {
            prefs.edit {
                remove(KEY_ACCOUNT_PREFIX + key)
                if (prefs.getString(KEY_SELECTED_ACCOUNT, null) == key) remove(KEY_SELECTED_ACCOUNT)
            }
            StoredAccountDto()
        }
    }

    @Synchronized
    fun write(context: Context, value: StoredAccountDto) {
        val prefs = preferences(context)
        writeAccount(context, prefs, value)
    }

    private fun writeAccount(context: Context, prefs: SharedPreferences, value: StoredAccountDto) {
        require(value.remember && value.email.isNotBlank())
        val normalized = value.copy(email = value.email.normalizedAccountEmail())
        val key = normalized.accountIdentity.storageKey
        val encrypted = aead(context).encrypt(
            StoredAccountCodec.encode(normalized),
            accountAssociatedData(key),
        )
        prefs.edit {
            putString(KEY_ACCOUNT_PREFIX + key, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            remove("email")
            remove("password")
        }
    }

    private fun accountAssociatedData(key: String): ByteArray =
        "org.aralozkaya.msngr.StoredAccountDto.account.$key".toByteArray(StandardCharsets.UTF_8)

    private fun preferences(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            val selected = prefs.getString(KEY_SELECTED_ACCOUNT, null)
            val emailEntries = prefs.all.keys.filter { it.startsWith(LEGACY_ACCOUNT_PREFIX) && it != KEY_BLOB }
            emailEntries.forEach { entry ->
                val oldKey = entry.removePrefix(LEGACY_ACCOUNT_PREFIX)
                val old = runCatching {
                    StoredAccountCodec.decode(aead(context).decrypt(
                        Base64.decode(prefs.getString(entry, ""), Base64.DEFAULT),
                        accountAssociatedData(oldKey),
                    )).also { require(accountStorageKey(it.email) == oldKey) }
                }.getOrNull()
                if (old != null && old.remember && old.email.isNotBlank()) {
                    writeAccount(context, prefs, old)
                    AccountPreferences.discardLegacy(context, old.email)
                    if (selected == oldKey) {
                        prefs.edit { putString(KEY_SELECTED_ACCOUNT, old.accountIdentity.storageKey) }
                    }
                }
                prefs.edit { remove(entry) }
            }
            val old = prefs.getString(KEY_BLOB, null)?.let { encoded ->
                runCatching {
                    StoredAccountCodec.decode(
                        aead(context).decrypt(Base64.decode(encoded, Base64.DEFAULT), associatedData),
                    )
                }.getOrDefault(StoredAccountDto())
            } ?: if (!prefs.getBoolean("accounts_migrated", false)) {
                migrateLegacy(context, prefs)
            } else {
                StoredAccountDto()
            }
            val owner = old.email.takeIf { old.remember }.orEmpty()
            if (owner.isNotBlank()) {
                writeAccount(context, prefs, old)
                prefs.edit { putString(KEY_SELECTED_ACCOUNT, old.accountIdentity.storageKey) }
            }
            AccountPreferences.discardLegacy(context, owner)
            prefs.edit {
                remove(KEY_BLOB)
                putBoolean(KEY_MIGRATED, true)
            }
        }
        return prefs
    }

    private fun migrateLegacy(context: Context, securePrefs: SharedPreferences): StoredAccountDto {
        val legacy = context.getSharedPreferences("msngr_session", Context.MODE_PRIVATE)
        val remember = legacy.getBoolean("save_password", false) &&
            legacy.getBoolean("login_complete", false)
        val email = readLegacyEncrypted(context, securePrefs, "email")
        val password = readLegacyEncrypted(context, securePrefs, "password")
        val servers = ServerOverrides(
            mainServer = legacy.getString("main_server", "").orEmpty(),
            configServer = legacy.getString("config_server", "").orEmpty(),
            authServerEnabled = legacy.getBoolean("auth_server_enabled", false),
            authServer = legacy.getString("auth_server", "").orEmpty(),
            addressBookServerEnabled = legacy.getBoolean("address_book_server_enabled", false),
            addressBookServer = legacy.getString("address_book_server", "").orEmpty(),
            adsServerEnabled = legacy.getBoolean("ads_server_enabled", false),
            adsServer = legacy.getString("ads_server", "").orEmpty(),
            storageServerEnabled = legacy.getBoolean("storage_server_enabled", false),
            storageServer = legacy.getString("storage_server", "").orEmpty(),
            socialServerEnabled = legacy.getBoolean("social_server_enabled", false),
            socialServer = legacy.getString("social_server", "").orEmpty(),
        )
        val migrated = StoredAccountDto(
            email = email.takeIf { remember } ?: "",
            password = password.takeIf { remember } ?: "",
            remember = remember && email.isNotBlank() && password.isNotEmpty(),
            provider = legacy.getString("provider", ProviderOptions.ESCARGOT).orEmpty(),
            customServers = servers,
        )
        legacy.edit { clear() }
        return migrated
    }

    private fun readLegacyEncrypted(
        context: Context,
        prefs: SharedPreferences,
        key: String,
    ): String {
        val encoded = prefs.getString(key, null) ?: return ""
        return try {
            val decrypted = aead(context).decrypt(
                Base64.decode(encoded, Base64.DEFAULT),
                key.toByteArray(StandardCharsets.UTF_8),
            )
            String(decrypted, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun aead(context: Context): Aead {
        cachedAead?.let { return it }
        return synchronized(this) {
            cachedAead?.let { return it }
            AeadConfig.register()
            AndroidKeysetManager.Builder()
                .withSharedPref(context.applicationContext, KEYSET_NAME, KEYSET_PREFS_NAME)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
                .also { cachedAead = it }
        }
    }
}
