package org.aralozkaya.msngr.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.appContainer

internal class NotificationShareCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return
        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID) ?: return
        val shareId = intent.getStringExtra(EXTRA_SHARE_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId >= 0) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
        val repository = context.applicationContext.appContainer.currentRepository ?: return
        val pendingResult = goAsync()
        scope.launch {
            try {
                when (kind) {
                    KIND_FILE -> repository.cancelFile(contactId, shareId)
                    KIND_PHOTO -> repository.stopPhotos(contactId, shareId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_KIND = "share_kind"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_SHARE_ID = "share_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val KIND_FILE = "file"
        const val KIND_PHOTO = "photo"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
