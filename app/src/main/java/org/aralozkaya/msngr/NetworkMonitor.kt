package org.aralozkaya.msngr

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first

internal class NetworkMonitor(context: Context) {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    suspend fun awaitInternetAccess() {
        if (hasInternetAccess()) return
        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(hasInternetAccess())
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    trySend(
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    )
                }
            }
            connectivityManager.registerDefaultNetworkCallback(callback)
            trySend(hasInternetAccess())
            awaitClose {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
        }.first { it }
    }

    private fun hasInternetAccess(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
