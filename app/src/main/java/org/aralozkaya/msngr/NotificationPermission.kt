package org.aralozkaya.msngr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.content.edit

internal fun Context.requestNotificationPermissionOnce(launcher: ActivityResultLauncher<String>) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val permission = Manifest.permission.POST_NOTIFICATIONS
    if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return
    val prefs = getSharedPreferences("notification_permission", Context.MODE_PRIVATE)
    if (prefs.getBoolean("requested", false)) return
    prefs.edit { putBoolean("requested", true) }
    launcher.launch(permission)
}
