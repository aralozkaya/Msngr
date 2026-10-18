package org.aralozkaya.msngr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccountIdentityTest {
    @Test
    fun identityNormalizesEmailButSeparatesMockAndRealServices() {
        val real = AccountIdentity.forLogin("person@example.com")
        assertEquals(real, AccountIdentity.forLogin(" PERSON@EXAMPLE.COM "))
        assertNotEquals(real, AccountIdentity.forLogin(real.email, useMockServer = true))
        assertNotEquals(real, AccountIdentity.forLogin(real.email, ProviderOptions.CROSSTALK))
        assertNotEquals(real.storageKey, AccountIdentity.forLogin(real.email, useMockServer = true).storageKey)
    }

    @Test
    fun customServersUseTheirHostAndPortWhileMockUsesItsOwnBackend() {
        val first = ServerOverrides(mainServer = "NS.EXAMPLE.COM", configServer = "config.example.com")
        val same = first.copy(mainServer = " ns.example.com ")
        val otherPort = first.copy(mainPort = 1865)
        assertEquals(
            AccountIdentity.forLogin("person@example.com", ProviderOptions.CUSTOM, first),
            AccountIdentity.forLogin("person@example.com", ProviderOptions.CUSTOM, same),
        )
        assertNotEquals(
            AccountIdentity.forLogin("person@example.com", ProviderOptions.CUSTOM, first),
            AccountIdentity.forLogin("person@example.com", ProviderOptions.CUSTOM, otherPort),
        )
        assertEquals(
            AccountIdentity.forLogin("person@example.com", useMockServer = true),
            AccountIdentity.forLogin("person@example.com", ProviderOptions.CUSTOM, first, useMockServer = true),
        )
    }
}
