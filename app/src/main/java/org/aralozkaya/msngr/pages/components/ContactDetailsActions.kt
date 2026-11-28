package org.aralozkaya.msngr.pages.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.SessionPrefs
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.isFavorite
import org.aralozkaya.msngr.localizeUserMessage

@Composable
internal fun ContactFavoriteButton(
    contact: Contact,
    repository: MessengerRepository,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var updating by remember(contact.id) { mutableStateOf(false) }
    IconToggleButton(
        checked = contact.isFavorite,
        enabled = !updating,
        modifier = modifier,
        onCheckedChange = { favorite ->
            if (!updating) {
                updating = true
                scope.launch {
                    val result = try {
                        repository.setContactFavorite(contact.id, favorite)
                    } finally {
                        updating = false
                    }
                    val message = result.fold(
                        onSuccess = { resources.getString(
                            if (favorite) R.string.contact_favorites_added else R.string.contact_favorites_removed,
                        ) },
                        onFailure = { it.message?.let(resources::localizeUserMessage)
                            ?: resources.getString(R.string.contact_categories_update_failed) },
                    )
                    snackbar.showActionSnackbar(message, isError = result.isFailure)
                }
            }
        },
    ) {
        Icon(
            imageVector = if (contact.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = stringResource(
                if (contact.isFavorite) R.string.contact_remove_from_favorites else R.string.contact_add_to_favorites,
            ),
            tint = if (contact.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ContactDetailsDialogs(
    repository: MessengerRepository,
    snackbar: SnackbarHostState,
    addContactEmail: String?,
    dismissAddContact: () -> Unit,
    manageCategoriesFor: Contact?,
    dismissCategories: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    if (addContactEmail != null) {
        val profile by repository.profile.collectAsState()
        val contacts by repository.contacts.collectAsState()
        val isMock = SessionPrefs.isMockServerActive()
        AddContactDialog(
            ownEmail = profile.email,
            contacts = contacts,
            initialEmail = addContactEmail,
            isMock = isMock,
            onAddContact = repository::addContact,
            onAdded = {
                dismissAddContact()
                scope.launch {
                    snackbar.showActionSnackbar(resources.getString(
                        if (isMock) R.string.contact_add_mock_success else R.string.contact_add_success,
                    ))
                }
            },
            onDismiss = dismissAddContact,
        )
    }
    if (manageCategoriesFor != null) {
        val categories by repository.categories.collectAsState()
        ContactCategoriesDialog(
            contact = manageCategoriesFor,
            categories = categories,
            onSave = { repository.setContactCategories(manageCategoriesFor.id, it) },
            onSaved = {
                dismissCategories()
                scope.launch { snackbar.showActionSnackbar(resources.getString(R.string.contact_categories_updated)) }
            },
            onDismiss = dismissCategories,
        )
    }
}
