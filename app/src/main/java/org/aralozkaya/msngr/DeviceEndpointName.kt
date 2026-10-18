package org.aralozkaya.msngr

import android.content.Context
import android.os.Build
import android.provider.Settings

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
