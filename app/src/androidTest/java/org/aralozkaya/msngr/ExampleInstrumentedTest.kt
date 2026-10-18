package org.aralozkaya.msngr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aralozkaya.msngr.data.MockServerData
import org.aralozkaya.msngr.enums.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureMockSessionInstrumentedTest {
    @Test
    fun encryptedMockSessionRoundTripsWithoutUsingRealServers() {
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .createDeviceProtectedStorageContext()

        try {
            SessionPrefs.completeLogin(
                context = context,
                email = MockServerData.ACCOUNT_EMAIL,
                password = MockServerData.ACCOUNT_PASSWORD,
                savePassword = true,
                provider = ProviderOptions.ESCARGOT,
                customServers = ServerOverrides(),
                useMockServer = true,
                automaticSignIn = true,
                loginPresence = Presence.Away,
            )

            val restored = SessionPrefs.readLoginSnapshot(context)
            assertEquals(MockServerData.ACCOUNT_EMAIL, restored.email)
            assertEquals(MockServerData.ACCOUNT_PASSWORD, restored.password)
            assertTrue(restored.savePassword)
            assertTrue(restored.useMockServer)
            assertTrue(restored.automaticSignIn)
            assertEquals(Presence.Away, restored.loginPresence)
            assertTrue(SessionPrefs.canOpenAuthenticatedScreens(context))
            assertTrue(SessionPrefs.isMockServerActive())

            SessionPrefs.setAutomaticSignIn(context, enabled = false)
            SessionPrefs.setLoginPresence(context, Presence.Busy)
            val changedOptions = SessionPrefs.readLoginSnapshot(context)
            assertFalse(changedOptions.automaticSignIn)
            assertEquals(Presence.Busy, changedOptions.loginPresence)
            SessionPrefs.setAutomaticSignIn(context, enabled = true)

            val encryptedBlob = context.getSharedPreferences(
                "msngr_session_secure",
                android.content.Context.MODE_PRIVATE,
            ).getString("profile_${restored.accountIdentity.storageKey}", "").orEmpty()
            assertTrue(encryptedBlob.isNotBlank())
            assertFalse(encryptedBlob.contains(MockServerData.ACCOUNT_EMAIL))
            assertFalse(encryptedBlob.contains(MockServerData.ACCOUNT_PASSWORD))

            SessionPrefs.saveUserProfile(
                context = context,
                username = MockServerData.ACCOUNT_NICKNAME,
                presence = Presence.Busy,
                statusMessage = MockServerData.ACCOUNT_STATUS_MESSAGE,
            )

            SessionPrefs.logout()
            val afterLogout = SessionPrefs.readLoginSnapshot(context)
            assertEquals(MockServerData.ACCOUNT_EMAIL, afterLogout.email)
            assertEquals(MockServerData.ACCOUNT_PASSWORD, afterLogout.password)
            assertTrue(afterLogout.savePassword)
            assertTrue(afterLogout.automaticSignIn)
            assertEquals(Presence.Busy, afterLogout.loginPresence)
            assertFalse(SessionPrefs.canOpenAuthenticatedScreens(context))
            assertFalse(SessionPrefs.isMockServerActive())
            val restoredProfile = SessionPrefs.mergeServerProfile(
                context = context,
                serverProfile = UserProfileSnapshot(
                    username = "Mock Server Default",
                    email = MockServerData.ACCOUNT_EMAIL,
                    presence = Presence.Online,
                    statusMessage = "Mock server default message",
                ),
            )
            assertEquals(MockServerData.ACCOUNT_NICKNAME, restoredProfile.username)
            assertEquals(MockServerData.ACCOUNT_STATUS_MESSAGE, restoredProfile.statusMessage)

            SessionPrefs.disableRememberedCredentials(context)
            val afterRememberDisabled = SessionPrefs.readLoginSnapshot(context)
            assertEquals("", afterRememberDisabled.password)
            assertFalse(afterRememberDisabled.savePassword)
            assertFalse(afterRememberDisabled.automaticSignIn)
        } finally {
            SessionPrefs.disableRememberedCredentials(context)
            SessionPrefs.logout()
        }

        assertFalse(SessionPrefs.canOpenAuthenticatedScreens(context))
        assertFalse(SessionPrefs.isMockServerActive())
    }
}
