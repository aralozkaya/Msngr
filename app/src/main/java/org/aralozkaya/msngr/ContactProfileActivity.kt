package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.aralozkaya.msngr.pages.ContactProfilePage
import org.aralozkaya.msngr.pages.components.ActionSnackbarHost
import org.aralozkaya.msngr.pages.components.ContactDetailsDialogs
import org.aralozkaya.msngr.pages.components.ContactFavoriteButton
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
            val categories by repository.categories.collectAsState()
            var addContactEmail by rememberSaveable { mutableStateOf<String?>(null) }
            var showManageCategories by rememberSaveable { mutableStateOf(false) }
            val snackbarHostState = remember { SnackbarHostState() }
            MsngrTheme {
                SecondaryPageScaffold(
                    title = stringResource(R.string.contact_profile_title),
                    onBack = ::finish,
                    actions = {
                        val current = contact
                        if (current != null && current.isForwardListContact && !current.isBlocked && !current.isGroup) {
                            ContactFavoriteButton(
                                contact = current,
                                repository = repository,
                                snackbar = snackbarHostState,
                                modifier = Modifier.testTag("contact_favorite_button"),
                            )
                        }
                    },
                    snackbarHost = { ActionSnackbarHost(snackbarHostState) },
                ) { padding, scroll ->
                    ContactProfilePage(
                        contact = contact,
                        innerPadding = padding,
                        scrollBehavior = scroll,
                        onBlockContact = { repository.blockContact(it.id) },
                        onEraseHistory = { repository.clearConversationHistory(it.id) },
                        onDeleteContact = { target, block -> repository.deleteContact(target.id, block) },
                        onContactDeleted = ::finish,
                        onAddContact = { addContactEmail = it.email },
                        categories = categories,
                        onManageCategories = { showManageCategories = true },
                    )
                }
                ContactDetailsDialogs(
                    repository = repository,
                    snackbar = snackbarHostState,
                    addContactEmail = addContactEmail,
                    dismissAddContact = { addContactEmail = null },
                    manageCategoriesFor = contact.takeIf { showManageCategories },
                    dismissCategories = { showManageCategories = false },
                )
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
