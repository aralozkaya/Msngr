package org.aralozkaya.msngr.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.aralozkaya.msngr.appContainer

class NotificationMarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)?.takeIf(String::isNotBlank) ?: return
        // Unread counts are local to the running repository. If the process was
        // restarted, there is no unread state to clear and no login is needed.
        val repository = runCatching { context.appContainer.messengerRepository }.getOrNull()
        repository?.markRead(contactId)
        MsngrNotificationManager(context).dismissConversation(contactId)
    }

    companion object {
        const val EXTRA_CONTACT_ID = "mark_read_contact_id"
    }
}
