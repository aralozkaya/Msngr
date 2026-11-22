package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.isFavorite
import org.aralozkaya.msngr.pages.ContactProfilePage
import org.aralozkaya.msngr.pages.components.ActionSnackbarHost
import org.aralozkaya.msngr.pages.components.AddContactDialog
import org.aralozkaya.msngr.pages.components.ContactCategoriesDialog
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.aralozkaya.msngr.pages.components.showActionSnackbar
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
            val contacts by repository.contacts.collectAsState()
            val categories by repository.categories.collectAsState()
            val profile by repository.profile.collectAsState()
            var addContactEmail by rememberSaveable { mutableStateOf<String?>(null) }
            var showManageCategories by rememberSaveable { mutableStateOf(false) }
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            MsngrTheme {
                SecondaryPageScaffold(
                    title = stringResource(R.string.contact_profile_title),
                    onBack = ::finish,
                    actions = {
                        val current = contact
                        if (current != null && current.isForwardListContact && !current.isBlocked && !current.isGroup) {
                            val isFav = current.isFavorite
                            IconButton(
                                onClick = {
                                    val nextFav = !isFav
                                    scope.launch {
                                        val result = repository.setContactFavorite(current.id, nextFav)
                                        if (result.isSuccess) {
                                            snackbarHostState.showActionSnackbar(
                                                getString(
                                                    if (nextFav) R.string.contact_favorites_added
                                                    else R.string.contact_favorites_removed,
                                                ),
                                            )
                                        } else {
                                            snackbarHostState.showActionSnackbar(
                                                result.exceptionOrNull()?.message
                                                    ?: getString(R.string.contact_categories_update_failed),
                                                isError = true,
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("contact_favorite_button"),
                            ) {
                                Icon(
                                    imageVector = if (isFav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                    contentDescription = stringResource(
                                        if (isFav) R.string.contact_remove_from_favorites
                                        else R.string.contact_add_to_favorites,
                                    ),
                                    tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                if (showManageCategories) {
                    contact?.let { current ->
                        ContactCategoriesDialog(
                            contact = current,
                            categories = categories,
                            onSave = { next -> repository.setContactCategories(current.id, next) },
                            onSaved = {
                                showManageCategories = false
                                scope.launch {
                                    snackbarHostState.showActionSnackbar(getString(R.string.contact_categories_updated))
                                }
                            },
                            onDismiss = { showManageCategories = false },
                        )
                    }
                }
                addContactEmail?.let { email ->
                    AddContactDialog(
                        ownEmail = profile.email,
                        contacts = contacts,
                        initialEmail = email,
                        isMock = SessionPrefs.isMockServerActive(),
                        onAddContact = repository::addContact,
                        onAdded = {
                            addContactEmail = null
                            scope.launch {
                                snackbarHostState.showActionSnackbar(getString(
                                    if (SessionPrefs.isMockServerActive()) R.string.contact_add_mock_success
                                    else R.string.contact_add_success,
                                ))
                            }
                        },
                        onDismiss = { addContactEmail = null },
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
