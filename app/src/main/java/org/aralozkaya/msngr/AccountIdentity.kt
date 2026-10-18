package org.aralozkaya.msngr

import java.security.MessageDigest
import java.util.Locale

/** An email on the mock server and on each real notification server is a separate account. */
@ConsistentCopyVisibility
data class AccountIdentity private constructor(val email: String, val service: String) {
    val storageId: String get() = "$email\u0000$service"
    val storageKey: String get() = MessageDigest.getInstance("SHA-256")
        .digest(storageId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        fun forLogin(
            email: String,
            provider: String = ProviderOptions.ESCARGOT,
            customServers: ServerOverrides = ServerOverrides(),
            useMockServer: Boolean = false,
        ): AccountIdentity = forServer(
            email, ServerPresets.resolveForProvider(provider, customServers), useMockServer,
        )

        fun forServer(email: String, server: ServerConfig, useMockServer: Boolean): AccountIdentity =
            AccountIdentity(
                email.normalizedAccountEmail(),
                if (useMockServer) "mock" else
                    "msnp://${server.mainServer.trim().lowercase(Locale.ROOT)}:${server.mainPort}",
            )
    }
}

val StoredAccountDto.accountIdentity: AccountIdentity
    get() = AccountIdentity.forLogin(email, provider, customServers, useMockServer)

val LoginSnapshot.accountIdentity: AccountIdentity
    get() = AccountIdentity.forLogin(email, provider, customServers, useMockServer)

internal val LoginSnapshot.accountLabel: String
    get() = "$email · ${if (useMockServer) "Mock" else if (provider == ProviderOptions.CUSTOM) resolvedServers.mainServer else provider}"
