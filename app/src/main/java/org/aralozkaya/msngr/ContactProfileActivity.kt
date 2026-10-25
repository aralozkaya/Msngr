package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.aralozkaya.msngr.pages.ContactProfilePage
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.aralozkaya.msngr.ui.theme.MsngrTheme

class ContactProfileActivity : MsngrActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectToLoginIfNeeded()) return
        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)
        if (contactId.isNullOrBlank()) {
            finish()
            return
        }
        val repository = appContainer.messengerRepository
        val contactFlow = repository.contact(contactId)
        setContent {
            LaunchedEffect(contactId) { repository.refreshContactPicture(contactId) }
            val contact by contactFlow.collectAsState(initial = repository.contactSnapshot(contactId))
            MsngrTheme {
                SecondaryPageScaffold(title = "Contact profile", onBack = ::finish) { padding, scroll ->
                    ContactProfilePage(
                        contact = contact,
                        innerPadding = padding,
                        scrollBehavior = scroll,
                        onBlockContact = { repository.blockContact(it.id) },
                        onEraseHistory = { repository.clearConversationHistory(it.id) },
                        onDeleteContact = { target, block -> repository.deleteContact(target.id, block) },
                        onContactDeleted = ::finish,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        redirectToLoginIfNeeded()
    }

    companion object {
        private const val EXTRA_CONTACT_ID = "contact_id"

        fun createIntent(context: Context, contactId: String): Intent =
            Intent(context, ContactProfileActivity::class.java).putExtra(EXTRA_CONTACT_ID, contactId)
    }
}
