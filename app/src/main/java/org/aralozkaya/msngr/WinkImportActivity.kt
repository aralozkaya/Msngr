@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.notifications.BackgroundConnectionController
import org.aralozkaya.msngr.pages.components.LoadingButtonContent
import org.aralozkaya.msngr.pages.components.MsngrCircularWavyProgressIndicator
import org.aralozkaya.msngr.protocol.MsnWink
import org.aralozkaya.msngr.protocol.MsnWinkPackage
import org.aralozkaya.msngr.protocol.WinkReference
import org.aralozkaya.msngr.ui.theme.MsngrTheme

/** Handles opening local `.mco` wink packages from Android file managers ("Open with" / "Open as"). */
class WinkImportActivity : MsngrActivity() {
    private var authReady by mutableStateOf(false)
    private var authInProgress by mutableStateOf(false)

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK || SessionPrefs.canOpenAuthenticatedScreens(this)) {
            authReady = true
            BackgroundConnectionController.startIfEnabled(this)
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetUri = extractViewUri(intent)
        ensureAuthenticated()
        setContent {
            MsngrTheme(fillWindowSurface = false, updateSystemBars = false) {
                WinkImportRoute(
                    authReady = authReady,
                    authInProgress = authInProgress,
                    targetUri = targetUri,
                    onFinish = ::finish,
                )
            }
        }
    }

    private fun extractViewUri(incoming: Intent): Uri? {
        val raw = incoming.data
            ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: return null
        return raw.takeIf(::isSafeExternalFileUri)
    }

    private fun ensureAuthenticated() {
        if (SessionPrefs.canOpenAuthenticatedScreens(this) && appContainer.currentRepository?.hasSignedInSession == true) {
            authReady = true
            BackgroundConnectionController.startIfEnabled(this)
            return
        }
        val snapshot = SessionPrefs.readLoginSnapshot(this)
        val canAutoSignIn = !SessionPrefs.wasRemotelySignedOut(this) &&
            snapshot.automaticSignIn &&
            SessionPrefs.hasRememberedCredentials(this)
        if (!canAutoSignIn) {
            loginLauncher.launch(LoginActivity.createReturnAfterLoginIntent(this))
            return
        }
        authInProgress = true
        lifecycleScope.launch {
            val repository = appContainer.repositoryFor(snapshot.useMockServer)
            val result = repository.signIn(
                SignInRequest(
                    email = snapshot.email,
                    password = snapshot.password,
                    serverConfig = snapshot.resolvedServers,
                    initialPresence = snapshot.loginPresence,
                ),
            )
            authInProgress = false
            result.onSuccess { serverProfile ->
                SessionPrefs.activateRememberedSession(this@WinkImportActivity)
                restoreSignedInProfile(
                    repository = repository,
                    serverProfile = serverProfile,
                    presence = serverProfile.presence,
                )
                authReady = true
                BackgroundConnectionController.startIfEnabled(this@WinkImportActivity)
            }.onFailure {
                SessionPrefs.invalidateRememberedCredentials(this@WinkImportActivity)
                loginLauncher.launch(LoginActivity.createReturnAfterLoginIntent(this@WinkImportActivity))
            }
        }
    }
}

@Composable
private fun WinkImportRoute(
    authReady: Boolean,
    authInProgress: Boolean,
    targetUri: Uri?,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    var mcoItems by remember { mutableStateOf<List<Pair<WinkReference, ByteArray>>>(emptyList()) }
    var parsing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var previewingWink by remember { mutableStateOf<Pair<WinkReference, ByteArray>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    if (!authReady || authInProgress) {
        AlertDialog(
            onDismissRequest = onFinish,
            title = { Text(stringResource(R.string.share_wink_import_dialog_title)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MsngrCircularWavyProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.share_signing_in))
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onFinish) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
        return
    }

    LaunchedEffect(targetUri) {
        if (targetUri == null) {
            errorMessage = resources.getString(R.string.share_error_no_file)
            return@LaunchedEffect
        }
        parsing = true
        errorMessage = null
        try {
            mcoItems = withContext(Dispatchers.IO) {
                val displayName = context.queryDocumentDisplayName(targetUri)
                val fallback = MsnWinkPackage.fallbackNameFromFileName(displayName)
                val bytes = context.contentResolver.openInputStream(targetUri)?.use {
                    it.readBytesBounded(MsnWink.MAX_CAB_BYTES)
                } ?: error(resources.getString(R.string.wink_import_failed))
                MsnWinkPackage.install(bytes, fallback)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            errorMessage = failure.message ?: resources.getString(R.string.wink_import_failed)
        } finally {
            parsing = false
        }
    }

    fun confirmImport() {
        if (importing || mcoItems.isEmpty()) return
        val account = context.winkAccount()
        if (account == null) {
            errorMessage = resources.getString(R.string.wink_sign_in_required)
            return
        }
        importing = true
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for ((reference, cabBytes) in mcoItems) {
                        context.winkStore().install(account, reference, cabBytes)
                    }
                }
                val toastText = context.getQuantityNumberSentence(
                    count = mcoItems.size,
                    oneBeforeRes = R.string.wink_added_count_singular_you_singular_before_number,
                    oneAfterRes = R.string.wink_added_count_singular_you_singular_after_number,
                    otherBeforeRes = R.string.wink_added_count_plural_you_singular_before_number,
                    otherAfterRes = R.string.wink_added_count_plural_you_singular_after_number,
                )
                Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
                onFinish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                errorMessage = failure.message ?: resources.getString(R.string.wink_import_failed)
            } finally {
                importing = false
            }
        }
    }

    val activePreview = previewingWink
    if (activePreview != null) {
        WinkOverlay(
            reference = activePreview.first,
            onDismiss = { previewingWink = null },
            packageBytes = activePreview.second,
        )
        return
    }

    if (parsing) {
        AlertDialog(
            onDismissRequest = onFinish,
            title = { Text(stringResource(R.string.share_wink_import_dialog_title)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MsngrCircularWavyProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.share_preparing))
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onFinish) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
        return
    }

    if (mcoItems.isEmpty()) {
        AlertDialog(
            onDismissRequest = onFinish,
            title = { Text(stringResource(R.string.share_wink_import_dialog_title)) },
            text = {
                Text(localizeUserMessage(errorMessage ?: stringResource(R.string.wink_import_failed)))
            },
            confirmButton = {
                Button(onClick = onFinish) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!importing) onFinish() },
        modifier = Modifier.testTag("wink_import_preview_dialog"),
        title = { Text(stringResource(R.string.share_wink_import_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                mcoItems.forEach { (reference, cabBytes) ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            WinkThumbnail(
                                reference = reference,
                                modifier = Modifier.size(56.dp),
                                packageBytes = cabBytes,
                            )
                            Text(
                                text = localizedWinkName(reference.name),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(
                                onClick = { previewingWink = reference to cabBytes },
                                enabled = !importing,
                                modifier = Modifier.testTag("share_wink_preview_button"),
                            ) {
                                Text(stringResource(R.string.common_preview))
                            }
                        }
                    }
                }
                errorMessage?.let { error ->
                    Text(
                        localizeUserMessage(error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::confirmImport,
                enabled = !importing && mcoItems.isNotEmpty(),
                modifier = Modifier.testTag("share_wink_import_confirm_button"),
            ) {
                LoadingButtonContent(
                    loading = importing,
                    label = stringResource(R.string.share_wink_import_confirm),
                ) {
                    Text(stringResource(R.string.share_wink_import_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onFinish,
                enabled = !importing,
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
