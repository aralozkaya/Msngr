package org.aralozkaya.msngr

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

/** MSNP identifies an installed client by this GUID, including cached photo requests. */
@Synchronized
internal fun deviceEndpointId(context: Context): String {
    val preferences = context.getSharedPreferences("msnp_endpoint", Context.MODE_PRIVATE)
    preferences.getString("id", null)?.let { return it }
    return UUID.randomUUID().toString().also {
        check(preferences.edit().putString("id", it).commit()) { "Could not save this device's Messenger identity." }
    }
}

/** The name shown to the same account's other Messenger clients. */
internal fun deviceEndpointName(context: Context? = null): String = resolveDeviceEndpointName(
    readDeviceName = {
        context?.let {
            // The Settings > About phone name; reading it needs no Bluetooth permission.
            Settings.Global.getString(it.contentResolver, "device_name")
        }
    },
    model = Build.MODEL,
)

internal fun resolveDeviceEndpointName(readDeviceName: () -> String?, model: String?): String =
    runCatching { readDeviceName() }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
        ?: model?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
        ?: "Android"
