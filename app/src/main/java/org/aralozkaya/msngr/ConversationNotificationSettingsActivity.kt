package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import org.aralozkaya.msngr.pages.ConversationNotificationSettingsScreen
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.aralozkaya.msngr.ui.theme.MsngrTheme

class ConversationNotificationSettingsActivity : MsngrActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectToLoginIfNeeded()) return
        val id = intent.getStringExtra("contact_id")?.takeIf { it.isNotBlank() } ?: run { finish(); return }
        val name = intent.getStringExtra("display_name") ?: id
        val group = intent.getBooleanExtra("is_group", false)
        setContent {
            MsngrTheme {
                SecondaryPageScaffold(title = "Custom notifications", onBack = ::finish) { padding, scroll ->
                    ConversationNotificationSettingsScreen(id, name, group, padding, scroll)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        redirectToLoginIfNeeded()
    }

    companion object {
        fun createIntent(context: Context, contactId: String, displayName: String, isGroup: Boolean): Intent =
            Intent(context, ConversationNotificationSettingsActivity::class.java).putExtra("contact_id", contactId)
                .putExtra("display_name", displayName).putExtra("is_group", isGroup)
    }
}
