package org.aralozkaya.msngr.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.SessionPrefs

class BackgroundConnectionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val snapshot = SessionPrefs.readLoginSnapshot(context)
                if (
                    canRestoreBackgroundConnection(
                        enabled = BackgroundDeliveryPrefs.keepsConnectionAlive(context),
                        snapshot = snapshot,
                    )
                ) {
                    BackgroundConnectionController.startIfEnabled(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
