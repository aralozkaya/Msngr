package org.aralozkaya.msngr

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.notifications.BackgroundDeliveryPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountStorageInstrumentedTest {
    @Test
    fun contactInvitationDefaultsFollowAccountServiceAndModeAndClearWithTheAccount() {
        login(alice)
        val realAccount = requireNotNull(SessionPrefs.preferenceAccount(context))
        val settings = ContactInvitationPrefs.state(context)
        assertEquals(ContactInvitationSettings(), settings.value)
        ContactInvitationPrefs.saveDeclineDefaults(context, block = true)
        assertEquals(ContactInvitationSettings(true, true), settings.value)
        SessionPrefs.logout()

        login(bob)
        assertEquals(ContactInvitationSettings(), settings.value)
        SessionPrefs.logout()
        login(alice, provider = ProviderOptions.CROSSTALK)
        assertEquals(ContactInvitationSettings(), settings.value)
        SessionPrefs.logout()
        login(alice, mock = true)
        assertEquals(ContactInvitationSettings(), settings.value)
        ContactInvitationPrefs.saveDeclineDefaults(context, block = false)
        assertEquals(ContactInvitationSettings(true, false), settings.value)
        SessionPrefs.logout()

        SessionPrefs.selectLoginAccount(context, realAccount)
        SessionPrefs.activateRememberedSession(context)
        assertEquals(ContactInvitationSettings(true, true), settings.value)
        ContactInvitationPrefs.setUseDeclineDefaults(context, false)
        assertEquals(ContactInvitationSettings(false, true), settings.value)
        SessionPrefs.disableRememberedCredentials(context, account = realAccount)
        assertEquals(ContactInvitationSettings(), ContactInvitationPrefs.state(context).value)
    }

    @Test
    fun automaticSessionLogoutDefaultsOffAndPersistsOnlyForItsAccount() {
        login(alice)
        val aliceAccount = requireNotNull(SessionPrefs.preferenceAccount(context))
        assertFalse(SessionPrefs.signsOutOtherSessionsOnSignIn(context))
        SessionPrefs.setSignOutOtherSessionsOnSignIn(context, true)
        SessionPrefs.logout()
        login(bob)
        assertFalse(SessionPrefs.signsOutOtherSessionsOnSignIn(context))
        assertTrue(SessionPrefs.signsOutOtherSessionsOnSignIn(context, aliceAccount))
        SessionPrefs.logout()
        SessionPrefs.selectLoginAccount(context, aliceAccount)
        SessionPrefs.activateRememberedSession(context)
        assertTrue(SessionPrefs.signsOutOtherSessionsOnSignIn(context))
        SessionPrefs.setSignOutOtherSessionsOnSignIn(context, false)
        assertFalse(SessionPrefs.signsOutOtherSessionsOnSignIn(context))
        SessionPrefs.setSignOutOtherSessionsOnSignIn(context, true)
        SessionPrefs.disableRememberedCredentials(context, account = aliceAccount)
        assertFalse(SessionPrefs.signsOutOtherSessionsOnSignIn(context, aliceAccount))
    }

    @Test
    fun remoteLogoutKeepsCredentialsButBlocksRestorationUntilExplicitLogin() {
        fun signIn(email: String) = SessionPrefs.completeLogin(context, email, "test-password", true,
            ProviderOptions.ESCARGOT, ServerOverrides(), useMockServer = true, automaticSignIn = true)
        signIn(alice)
        val account = requireNotNull(SessionPrefs.preferenceAccount(context))
        SessionPrefs.recordRemoteSignOut(context)
        SessionPrefs.logout()
        assertTrue(SessionPrefs.wasRemotelySignedOut(context, account))
        assertTrue(SessionPrefs.readLoginSnapshot(context, account).savePassword)
        assertFalse(SessionPrefs.readLoginSnapshot(context, account).automaticSignIn)
        signIn(bob)
        assertFalse(SessionPrefs.wasRemotelySignedOut(context))
        assertTrue(SessionPrefs.wasRemotelySignedOut(context, account))
        signIn(alice)
        assertFalse(SessionPrefs.wasRemotelySignedOut(context, account))
    }

    private lateinit var context: Context
    private val alice = "alice-storage@example.com"
    private val bob = "bob-storage@example.com"
    private val customServers = ServerOverrides(
        mainServer = "alice-notification.example.com",
        configServer = "alice-config.example.com",
    )

    @Before
    fun prepareStorage() {
        SessionPrefs.logout()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        context = object : ContextWrapper(target.createDeviceProtectedStorageContext()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "msngr_session_secure_keyset") {
                    target.applicationContext.getSharedPreferences(name, mode)
                } else {
                    super.getSharedPreferences("account_storage_test_$name", mode)
                }

            override fun getApplicationContext(): Context = this
            override fun createDeviceProtectedStorageContext(): Context = this
        }
        SessionPrefs.rememberedProfiles(context).forEach { AccountPreferences.clear(context, it.accountIdentity) }
        context.getSharedPreferences("msngr_session_secure", Context.MODE_PRIVATE)
            .edit().clear().commit()
        listOf("msngr_profile", "msngr_appearance", "msngr_emoticons").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After
    fun clearStorage() {
        SessionPrefs.logout()
        SessionPrefs.rememberedProfiles(context).forEach {
            SessionPrefs.disableRememberedCredentials(context, account = it.accountIdentity)
        }
    }

    @Test
    fun profileFieldsAndLivePreferencesFollowTheSignedInAccount() {
        login(alice, provider = ProviderOptions.CUSTOM, presence = Presence.Away)
        val appearance = AppearancePrefs.state(context)
        val emoticons = EmoticonPrefs.state(context)
        val plus = MessengerPlusPrefs.state(context)
        SessionPrefs.saveUserProfile(context, "Alice", Presence.Busy, "content://alice/photo", "Alice message")
        AppearancePrefs.setThemeMode(context, ThemeMode.Dark)
        AppearancePrefs.setColorMode(context, ColorMode.Default)
        EmoticonPrefs.setConvertTypedShortcuts(context, false)
        EmoticonPrefs.setUseOriginalEmoticons(context, false)
        MessengerPlusPrefs.setEnabled(context, false)
        MessengerPlusPrefs.setHideControlCharacters(context, false)
        BackgroundDeliveryPrefs.setKeepsConnectionAlive(context, false)
        SessionPrefs.logout()

        login(bob, provider = ProviderOptions.CROSSTALK)
        assertEquals(serverProfile(bob), SessionPrefs.mergeServerProfile(context, serverProfile(bob)))
        assertEquals(AppAppearance(), appearance.value)
        assertEquals(EmoticonSettings(), emoticons.value)
        assertEquals(MessengerPlusSettings(), plus.value)
        assertTrue(BackgroundDeliveryPrefs.keepsConnectionAlive(context))
        assertEquals(ProviderOptions.CROSSTALK, SessionPrefs.readLoginSnapshot(context).provider)
        SessionPrefs.saveUserProfile(context, "Bob", Presence.Online, "content://bob/photo", "Bob message")
        SessionPrefs.logout()

        // Recreate the validated session entirely from stored bytes, including differently cased email.
        SessionPrefs.selectLoginAccount(context, "  ALICE-STORAGE@EXAMPLE.COM  ")
        SessionPrefs.activateRememberedSession(context)
        val restored = SessionPrefs.mergeServerProfile(context, serverProfile(alice))
        assertEquals("Alice", restored.username)
        assertEquals("Alice message", restored.statusMessage)
        assertEquals("content://alice/photo", restored.avatarUri)
        assertEquals(Presence.Busy, restored.presence)
        assertEquals(AppAppearance(ThemeMode.Dark, ColorMode.Default), appearance.value)
        assertEquals(EmoticonSettings(false, false), emoticons.value)
        assertEquals(MessengerPlusSettings(false, false), plus.value)
        assertFalse(BackgroundDeliveryPrefs.keepsConnectionAlive(context))
        val snapshot = SessionPrefs.readLoginSnapshot(context)
        assertEquals(ProviderOptions.CUSTOM, snapshot.provider)
        assertEquals(customServers, snapshot.customServers)
        assertEquals(Presence.Away, snapshot.loginPresence)
        assertEquals("password-$alice", snapshot.password)
        assertEquals(listOf(alice, bob), SessionPrefs.rememberedAccounts(context))
    }

    @Test
    fun forgettingOneProfileImmediatelyDeletesOnlyItsCredentialsAndPreferences() {
        login(alice)
        SessionPrefs.saveUserProfile(context, "Alice", Presence.Away, "content://alice/photo", "Private")
        AppearancePrefs.setThemeMode(context, ThemeMode.Dark)
        SessionPrefs.logout()
        login(bob)
        SessionPrefs.saveUserProfile(context, "Bob", Presence.Busy, statusMessage = "Bob message")
        SessionPrefs.logout()

        SessionPrefs.selectLoginAccount(context, alice)
        SessionPrefs.disableRememberedCredentials(context, alice)

        assertEquals(listOf(bob), SessionPrefs.rememberedAccounts(context))
        assertEquals("", SessionPrefs.readLoginSnapshot(context).email)
        assertEquals("", SessionPrefs.readLoginSnapshot(context, alice).password)
        assertEquals(serverProfile(alice), SessionPrefs.mergeServerProfile(context, serverProfile(alice)))
        assertTrue(AccountPreferences.forAccount(context, "msngr_appearance", alice).all.isEmpty())
        assertEquals("Bob", SessionPrefs.mergeServerProfile(context, serverProfile(bob)).username)
        assertEquals("password-$bob", SessionPrefs.readLoginSnapshot(context, bob).password)
        assertFalse(SessionPrefs.canOpenAuthenticatedScreens(context))
    }

    @Test
    fun disablingAutomaticSignInKeepsTheProfileForManualLogin() {
        login(alice)
        SessionPrefs.saveUserProfile(context, "Alice", Presence.Away, statusMessage = "Saved message")
        SessionPrefs.logout()

        SessionPrefs.setAutomaticSignIn(context, false, alice)

        val snapshot = SessionPrefs.readLoginSnapshot(context, alice)
        assertTrue(snapshot.savePassword)
        assertFalse(snapshot.automaticSignIn)
        assertEquals("password-$alice", snapshot.password)
        assertEquals("Saved message", SessionPrefs.mergeServerProfile(context, serverProfile(alice)).statusMessage)
    }

    @Test
    fun unrememberedSessionsKeepTheirOwnSettingsOnlyUntilLogout() {
        login(alice)
        SessionPrefs.saveUserProfile(context, "Alice", Presence.Away, statusMessage = "Alice message")
        SessionPrefs.logout()

        login(bob, remember = false, provider = ProviderOptions.CROSSTALK)
        assertEquals(bob, SessionPrefs.readLoginSnapshot(context).email)
        assertEquals(ProviderOptions.CROSSTALK, SessionPrefs.readLoginSnapshot(context).provider)
        SessionPrefs.saveUserProfile(context, "Temporary Bob", Presence.Busy, statusMessage = "Temporary")
        EmoticonPrefs.setConvertTypedShortcuts(context, false)
        assertEquals("Temporary Bob", SessionPrefs.readUserProfile(context).username)
        SessionPrefs.logout()

        assertEquals(listOf(alice), SessionPrefs.rememberedAccounts(context))
        assertEquals("", SessionPrefs.readLoginSnapshot(context).email)
        assertEquals(serverProfile(bob), SessionPrefs.mergeServerProfile(context, serverProfile(bob)))
        assertTrue(AccountPreferences.forAccount(context, "msngr_emoticons", bob).all.isEmpty())
        assertEquals("Alice message", SessionPrefs.mergeServerProfile(context, serverProfile(alice)).statusMessage)

        login(bob)
        assertEquals(EmoticonSettings(), EmoticonPrefs.state(context).value)
    }

    @Test
    fun editingANewLoginDoesNotChangeAnExistingAccountsOptions() {
        login(alice, provider = ProviderOptions.CUSTOM, presence = Presence.Away)
        SessionPrefs.logout()

        SessionPrefs.selectLoginAccount(context, bob)
        SessionPrefs.setLoginPresence(context, Presence.Busy, bob)
        SessionPrefs.saveServerSettings(context, ProviderOptions.CROSSTALK, ServerOverrides(), bob)
        SessionPrefs.setAutomaticSignIn(context, true, bob)

        val aliceOptions = SessionPrefs.readLoginSnapshot(context, alice)
        assertEquals(ProviderOptions.CUSTOM, aliceOptions.provider)
        assertEquals(customServers, aliceOptions.customServers)
        assertEquals(Presence.Away, aliceOptions.loginPresence)
        assertTrue(aliceOptions.automaticSignIn)
        val newOptions = SessionPrefs.readLoginSnapshot(context, bob)
        assertEquals("", newOptions.password)
        assertEquals(ProviderOptions.ESCARGOT, newOptions.provider)
        assertEquals(Presence.Online, newOptions.loginPresence)
        assertFalse(newOptions.automaticSignIn)
    }

    @Test
    fun rejectedSavedCredentialsKeepProfileEditsAndOtherAccounts() {
        login(bob)
        SessionPrefs.logout()
        login(alice, provider = ProviderOptions.CUSTOM)
        SessionPrefs.saveUserProfile(context, "Alice", Presence.Away, statusMessage = "Keep me")
        SessionPrefs.logout()

        SessionPrefs.invalidateRememberedCredentials(context)

        assertFalse(SessionPrefs.hasRememberedCredentials(context))
        assertTrue(SessionPrefs.readLoginSnapshot(context).savePassword)
        assertFalse(SessionPrefs.readLoginSnapshot(context).automaticSignIn)
        assertEquals(ProviderOptions.CUSTOM, SessionPrefs.readLoginSnapshot(context).provider)
        assertEquals("Keep me", SessionPrefs.mergeServerProfile(context, serverProfile(alice)).statusMessage)
        assertEquals("password-$bob", SessionPrefs.readLoginSnapshot(context, bob).password)
    }

    @Test
    fun mockAndRealAccountsWithTheSameEmailKeepEveryProfileSettingSeparate() {
        val mock = AccountIdentity.forLogin(alice, useMockServer = true)
        val real = AccountIdentity.forLogin(alice)
        login(alice, mock = true, presence = Presence.Busy)
        SessionPrefs.saveUserProfile(context, "Mock Alice", Presence.Busy, "content://mock/photo", "Mock message")
        val appearance = AppearancePrefs.state(context)
        val emoticons = EmoticonPrefs.state(context)
        val plus = MessengerPlusPrefs.state(context)
        AppearancePrefs.setThemeMode(context, ThemeMode.Dark)
        EmoticonPrefs.setConvertTypedShortcuts(context, false)
        MessengerPlusPrefs.setEnabled(context, false)
        BackgroundDeliveryPrefs.setKeepsConnectionAlive(context, false)
        SessionPrefs.logout()

        login(alice, presence = Presence.Away)
        assertEquals(serverProfile(alice), SessionPrefs.mergeServerProfile(context, serverProfile(alice)))
        assertEquals(AppAppearance(), appearance.value)
        assertEquals(EmoticonSettings(), emoticons.value)
        assertEquals(MessengerPlusSettings(), plus.value)
        assertTrue(BackgroundDeliveryPrefs.keepsConnectionAlive(context))
        SessionPrefs.saveUserProfile(context, "Real Alice", Presence.Away, "content://real/photo", "Real message")
        AppearancePrefs.setThemeMode(context, ThemeMode.Light)
        SessionPrefs.logout()

        assertEquals(2, SessionPrefs.rememberedProfiles(context).size)
        assertTrue(SessionPrefs.readLoginSnapshot(context, mock).useMockServer)
        assertFalse(SessionPrefs.readLoginSnapshot(context, real).useMockServer)
        assertEquals(Presence.Busy, SessionPrefs.readLoginSnapshot(context, mock).loginPresence)
        assertEquals(Presence.Away, SessionPrefs.readLoginSnapshot(context, real).loginPresence)
        SessionPrefs.selectLoginAccount(context, mock)
        SessionPrefs.activateRememberedSession(context)
        assertEquals("Mock Alice", SessionPrefs.readUserProfile(context).username)
        assertEquals("Mock message", SessionPrefs.readUserProfile(context).statusMessage)
        assertEquals("content://mock/photo", SessionPrefs.readUserProfile(context).avatarUri)
        assertEquals(ThemeMode.Dark, appearance.value.themeMode)
        assertFalse(emoticons.value.convertTypedShortcuts)
        assertFalse(plus.value.enabled)
        assertFalse(BackgroundDeliveryPrefs.keepsConnectionAlive(context))
        SessionPrefs.logout()

        SessionPrefs.disableRememberedCredentials(context, account = mock)
        assertFalse(SessionPrefs.readLoginSnapshot(context, mock).savePassword)
        assertTrue(SessionPrefs.readLoginSnapshot(context, real).savePassword)
        SessionPrefs.selectLoginAccount(context, real)
        SessionPrefs.activateRememberedSession(context)
        assertEquals("Real Alice", SessionPrefs.readUserProfile(context).username)
        assertEquals("Real message", SessionPrefs.readUserProfile(context).statusMessage)
        assertEquals("content://real/photo", SessionPrefs.readUserProfile(context).avatarUri)
        assertEquals(ThemeMode.Light, appearance.value.themeMode)
    }

    @Test
    fun sameEmailOnDifferentRealServicesDoesNotShareProfileOverrides() {
        val escargot = AccountIdentity.forLogin(alice)
        val crossTalk = AccountIdentity.forLogin(alice, ProviderOptions.CROSSTALK)
        login(alice)
        SessionPrefs.saveUserProfile(context, "Escargot Alice", Presence.Busy, statusMessage = "Escargot message")
        SessionPrefs.logout()
        login(alice, provider = ProviderOptions.CROSSTALK)
        assertEquals(serverProfile(alice), SessionPrefs.mergeServerProfile(context, serverProfile(alice)))
        SessionPrefs.saveUserProfile(context, "CrossTalk Alice", Presence.Away, statusMessage = "CrossTalk message")
        SessionPrefs.logout()

        assertEquals("Escargot Alice", SessionPrefs.mergeServerProfile(context, serverProfile(alice), escargot).username)
        assertEquals("CrossTalk Alice", SessionPrefs.mergeServerProfile(context, serverProfile(alice), crossTalk).username)
        SessionPrefs.disableRememberedCredentials(context, account = crossTalk)
        assertTrue(SessionPrefs.readLoginSnapshot(context, escargot).savePassword)
    }

    @Test
    fun encryptedAccountEntriesCannotBeCopiedIntoAnotherAccount() {
        login(alice)
        SessionPrefs.logout()
        login(bob)
        SessionPrefs.logout()
        val prefs = context.getSharedPreferences("msngr_session_secure", Context.MODE_PRIVATE)
        val aliceKey = "profile_${AccountIdentity.forLogin(alice).storageKey}"
        val bobKey = "profile_${AccountIdentity.forLogin(bob).storageKey}"
        prefs.edit().putString(aliceKey, prefs.getString(bobKey, null)).commit()

        assertEquals("", SessionPrefs.readLoginSnapshot(context, alice).password)
        assertEquals("password-$bob", SessionPrefs.readLoginSnapshot(context, bob).password)
        assertEquals(listOf(bob), SessionPrefs.rememberedAccounts(context))
    }

    @Test
    fun emailOnlyMockCredentialsMigrateWithoutRestoringSharedProfileEditsIntoRealMode() {
        AeadConfig.register()
        val aead = AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, "msngr_session_secure_keyset", "msngr_session_secure_keyset")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://msngr_session_master_key")
            .build().keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        val oldKey = accountStorageKey(alice)
        val encoded = Base64.encodeToString(aead.encrypt(
            StoredAccountCodec.encode(StoredAccountDto(alice, "mock-password", remember = true, useMockServer = true)),
            "org.aralozkaya.msngr.StoredAccountDto.account.$oldKey".toByteArray(Charsets.UTF_8),
        ), Base64.NO_WRAP)
        val secure = context.getSharedPreferences("msngr_session_secure", Context.MODE_PRIVATE)
        secure.edit().putBoolean("accounts_migrated", true).putString("selected_account", oldKey)
            .putString("account_$oldKey", encoded).commit()
        val legacy = context.getSharedPreferences("msngr_profile_$oldKey", Context.MODE_PRIVATE)
        legacy.edit().putString("username", "Mock nickname").putString("status_message", "Mock message").commit()

        val mock = AccountIdentity.forLogin(alice, useMockServer = true)
        val real = AccountIdentity.forLogin(alice)
        assertEquals("mock-password", SessionPrefs.readLoginSnapshot(context, mock).password)
        assertEquals(mock, SessionPrefs.readLoginSnapshot(context).accountIdentity)
        assertEquals("", SessionPrefs.readLoginSnapshot(context, real).password)
        assertEquals(serverProfile(alice), SessionPrefs.mergeServerProfile(context, serverProfile(alice), real))
        assertFalse(secure.contains("account_$oldKey"))
        assertTrue(legacy.all.isEmpty())
    }

    @Test
    fun legacySharedProfileIsResetWhileItsRememberedCredentialsMigrate() {
        AeadConfig.register()
        val aead = AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, "msngr_session_secure_keyset", "msngr_session_secure_keyset")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://msngr_session_master_key")
            .build().keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        val bytes = aead.encrypt(
            StoredAccountCodec.encode(StoredAccountDto(alice, "legacy-password", remember = true)),
            "org.aralozkaya.msngr.StoredAccountDto.v2".toByteArray(Charsets.UTF_8),
        )
        val secure = context.getSharedPreferences("msngr_session_secure", Context.MODE_PRIVATE)
        secure.edit().putString("account_dto_v2", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        val legacy = context.getSharedPreferences("msngr_profile", Context.MODE_PRIVATE)
        legacy.edit().putString("username", "Legacy Alice").putString("status_message", "Legacy message").commit()

        assertEquals("legacy-password", SessionPrefs.readLoginSnapshot(context).password)
        assertEquals(serverProfile(alice), SessionPrefs.mergeServerProfile(context, serverProfile(alice)))
        assertTrue(legacy.all.isEmpty())
        assertFalse(secure.contains("account_dto_v2"))

        login(bob)
        assertEquals(serverProfile(bob), SessionPrefs.mergeServerProfile(context, serverProfile(bob)))
        assertEquals(serverProfile(alice), SessionPrefs.mergeServerProfile(context, serverProfile(alice)))
    }

    private fun login(
        email: String,
        remember: Boolean = true,
        provider: String = ProviderOptions.ESCARGOT,
        presence: Presence = Presence.Online,
        mock: Boolean = false,
    ) {
        SessionPrefs.completeLogin(
            context, email, "password-$email", remember, provider,
            if (provider == ProviderOptions.CUSTOM) customServers else ServerOverrides(),
            useMockServer = mock,
            automaticSignIn = true,
            loginPresence = presence,
        )
    }

    private fun serverProfile(email: String) = UserProfileSnapshot(
        "Server ${email.substringBefore('@')}", email, Presence.Online,
        "content://server/photo", "Server message",
    )
}
