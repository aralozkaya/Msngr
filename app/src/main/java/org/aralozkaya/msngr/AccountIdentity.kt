package org.aralozkaya.msngr

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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

internal fun LoginSnapshot.displayAccountLabel(context: Context): String {
    val service = when {
        useMockServer -> context.getString(R.string.account_provider_mock)
        provider == ProviderOptions.CUSTOM -> resolvedServers.mainServer
        else -> context.providerDisplayName(provider)
    }
    return context.getString(R.string.account_dropdown_label_format, email, service)
}

@Composable
internal fun LoginSnapshot.displayAccountLabel(): String {
    val service = when {
        useMockServer -> stringResource(R.string.account_provider_mock)
        provider == ProviderOptions.CUSTOM -> resolvedServers.mainServer
        else -> providerDisplayName(provider)
    }
    return stringResource(R.string.account_dropdown_label_format, email, service)
}
