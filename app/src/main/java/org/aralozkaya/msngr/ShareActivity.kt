@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.recipientFeatures
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.components.Avatar
import org.aralozkaya.msngr.pages.components.LoadingButtonContent
import org.aralozkaya.msngr.pages.components.MsngrCircularWavyProgressIndicator
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import java.io.File
import java.util.Locale

private const val MAX_PHOTO_SHARE_COUNT = 32
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

internal enum class ExternalShareMode { Files, Photos }

private enum class ShareFlowStep {
    Preparing,
    Error,
    Ready,
}

private data class SharedItemInfo(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val isPicture: Boolean,
    val thumbnail: ImageBitmap? = null,
)

private data class RecipientEvaluation(
    val contact: Contact,
    val eligible: Boolean,
    val statusNote: String?,
    val sortBucket: Int,
)

/** Native Android share target for regular single-file transfers; see [SharePhotosActivity] for Photos. */
open class ShareActivity : ExternalDocumentActivity() {
    internal open val shareMode: ExternalShareMode = ExternalShareMode.Files

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val incomingUris = extractIncomingUris(intent)
        setContent {
            MsngrTheme {
                ShareRoute(
                    appContainer = appContainer,
                    shareMode = shareMode,
                    authReady = authenticated,
                    incomingUris = incomingUris,
                    initialMessageText = initialText,
                    onFinish = ::finish,
                )
            }
        }
    }

    override fun onSessionReady() {
        requestNotificationPermissionOnce(notificationPermissionRequest)
    }

    private fun extractIncomingUris(incoming: Intent): List<Uri> {
        val raw = mutableListOf<Uri>()
        when (incoming.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)?.let(raw::add)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let(raw::addAll)
            }
        }
        if (raw.isEmpty()) {
            incoming.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) {
                    clip.getItemAt(index)?.uri?.let(raw::add)
                }
            }
        }
        return raw.distinct().filter(::isSafeExternalFileUri)
    }
}

/** Dedicated Android share target for Photo sharing (single and multiple images). */
class SharePhotosActivity : ShareActivity() {
    override val shareMode: ExternalShareMode = ExternalShareMode.Photos
}

@Composable
private fun ShareRoute(
    appContainer: AppContainer,
    shareMode: ExternalShareMode,
    authReady: Boolean,
    incomingUris: List<Uri>,
    initialMessageText: String,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    if (!authReady) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MsngrCircularWavyProgressIndicator()
                    Text(
                        stringResource(R.string.share_signing_in),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    val repository = appContainer.messengerRepository
    val contacts by repository.contacts.collectAsState()
    val profile by repository.profile.collectAsState()
    val emoticonSettings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    val messengerPlusSettings by remember(context) { MessengerPlusPrefs.state(context) }.collectAsState()

    var flowStep by remember { mutableStateOf(ShareFlowStep.Preparing) }
    var sharedItems by remember { mutableStateOf<List<SharedItemInfo>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var selectedContactId by rememberSaveable { mutableStateOf<String?>(null) }
    var optionalMessage by rememberSaveable {
        mutableStateOf(if (shareMode == ExternalShareMode.Files && incomingUris.size == 1) initialMessageText else "")
    }
    var sending by remember { mutableStateOf(false) }
    var goingOnline by remember { mutableStateOf(false) }
    val searchState = rememberTextFieldState()

    LaunchedEffect(incomingUris, shareMode) {
        if (incomingUris.isEmpty()) {
            errorMessage = resources.getString(R.string.share_error_no_file)
            flowStep = ShareFlowStep.Error
            return@LaunchedEffect
        }
        flowStep = ShareFlowStep.Preparing
        val classified = withContext(Dispatchers.IO) {
            incomingUris.mapIndexed { index, uri ->
                inspectSharedUri(context, uri, decodeThumbnail = index < 4)
            }
        }
        sharedItems = classified
        when (shareMode) {
            ExternalShareMode.Files -> {
                if (classified.size > 1) {
                    errorMessage = resources.getString(R.string.share_error_multiple_non_photos)
                    flowStep = ShareFlowStep.Error
                } else {
                    flowStep = ShareFlowStep.Ready
                }
            }
            ExternalShareMode.Photos -> {
                when {
                    classified.any { !it.isPicture } -> {
                        errorMessage = resources.getString(
                            if (classified.size > 1) R.string.share_error_multiple_non_photos
                            else R.string.photo_sharing_unsupported_image,
                        )
                        flowStep = ShareFlowStep.Error
                    }
                    classified.size > MAX_PHOTO_SHARE_COUNT -> {
                        errorMessage = context.getNumberSentence(
                            R.string.photo_sharing_limit_before_number,
                            MAX_PHOTO_SHARE_COUNT,
                            R.string.photo_sharing_limit_after_number,
                        )
                        flowStep = ShareFlowStep.Error
                    }
                    else -> flowStep = ShareFlowStep.Ready
                }
            }
        }
    }

    val offlineLabel = stringResource(R.string.share_recipient_offline)
    val appearOfflineNote = stringResource(R.string.share_recipient_unavailable_appear_offline_you_singular)
    val unsupportedFilesLabel = stringResource(R.string.share_recipient_unsupported_files)
    val unsupportedPhotosLabel = stringResource(R.string.share_recipient_unsupported_photos)
    val ownOffline = !profile.presence.isOnline

    val query = searchState.text.toString().trim()
    val evaluatedContacts = remember(contacts, profile.email, profile.presence, shareMode, query) {
        contacts.asSequence()
            .filter { !it.isGroup && !it.isBlocked }
            .filter { contact ->
                query.isBlank() ||
                    contact.displayName.messengerPlusPlainText().contains(query, ignoreCase = true) ||
                    contact.email.contains(query, ignoreCase = true)
            }
            .map { contact ->
                val features = contact.recipientFeatures(profile.email)
                val (eligible, note, bucket) = when {
                    ownOffline -> Triple(false, appearOfflineNote, if (contact.presence.isOnline) 1 else 2)
                    !contact.presence.isOnline -> Triple(false, offlineLabel, 2)
                    shareMode == ExternalShareMode.Files && !features.files ->
                        Triple(false, unsupportedFilesLabel, 1)
                    shareMode == ExternalShareMode.Photos && !features.photos ->
                        Triple(false, unsupportedPhotosLabel, 1)
                    else -> Triple(true, null, 0)
                }
                RecipientEvaluation(
                    contact = contact,
                    eligible = eligible,
                    statusNote = note,
                    sortBucket = bucket,
                )
            }
            .sortedWith(
                compareBy<RecipientEvaluation> { it.sortBucket }
                    .thenBy { it.contact.displayName.ifBlank { it.contact.email }.lowercase(Locale.ROOT) },
            )
            .toList()
    }

    LaunchedEffect(evaluatedContacts, selectedContactId) {
        val currentId = selectedContactId ?: return@LaunchedEffect
        val stillEligible = evaluatedContacts.any { it.contact.id == currentId && it.eligible }
        if (!stillEligible) selectedContactId = null
    }

    val selectedEvaluation = evaluatedContacts.firstOrNull { it.contact.id == selectedContactId && it.eligible }

    fun sendSelected() {
        val target = selectedEvaluation?.contact ?: return
        if (sending || sharedItems.isEmpty()) return
        sending = true
        errorMessage = null
        scope.launch {
            try {
                val liveProfile = repository.profile.value
                val liveContact = repository.contacts.value.firstOrNull { it.id == target.id }
                val liveFeatures = liveContact?.recipientFeatures(liveProfile.email)
                check(liveProfile.presence.isOnline && liveContact != null && !liveContact.isGroup && !liveContact.isBlocked && liveContact.presence.isOnline) {
                    resources.getString(R.string.share_recipient_offline)
                }
                when (shareMode) {
                    ExternalShareMode.Files -> {
                        check(liveFeatures?.files == true) { resources.getString(R.string.share_recipient_unsupported_files) }
                        val singleUri = sharedItems.single().uri
                        val (stagedFile, stagedName) = withContext(Dispatchers.IO) {
                            stageSharedFile(context, singleUri)
                        }
                        try {
                            val noteText = optionalMessage.trim()
                            if (noteText.isNotEmpty()) {
                                val outgoing = noteText.toMsnOutgoingMessage(
                                    convertTypedShortcuts = emoticonSettings.convertTypedShortcuts,
                                    messengerPlusEnabled = messengerPlusSettings.enabled,
                                )
                                if (outgoing.text.isNotEmpty()) {
                                    repository.sendMessage(target.id, outgoing.text, outgoing.renderedEmoticonRanges)
                                }
                            }
                            repository.sendFile(target.id, stagedFile, stagedName).getOrThrow()
                        } catch (failure: Exception) {
                            stagedFile.delete()
                            throw failure
                        }
                    }
                    ExternalShareMode.Photos -> {
                        check(liveFeatures?.photos == true) { resources.getString(R.string.share_recipient_unsupported_photos) }
                        val photos = withContext(Dispatchers.IO) {
                            prepareSharedPhotos(context, sharedItems.map { it.uri })
                        }
                        repository.sharePhotos(target.id, photos).getOrThrow()
                    }
                }
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val chatIntent = ChatActivity.createIntent(
                    context = context,
                    contactId = target.id,
                    openPhotoShare = shareMode == ExternalShareMode.Photos,
                )
                context.startActivities(arrayOf(mainIntent, chatIntent))
                onFinish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                errorMessage = failure.message ?: resources.getString(
                    if (shareMode == ExternalShareMode.Photos) R.string.photo_sharing_failed
                    else R.string.file_sharing_failed,
                )
            } finally {
                sending = false
            }
        }
    }

    when (flowStep) {
        ShareFlowStep.Preparing -> {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        MsngrCircularWavyProgressIndicator()
                        Text(
                            stringResource(R.string.share_preparing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        ShareFlowStep.Error -> {
            AlertDialog(
                onDismissRequest = onFinish,
                title = { Text(stringResource(R.string.app_name)) },
                text = {
                    Text(localizeUserMessage(errorMessage ?: stringResource(R.string.share_error_no_file)))
                },
                confirmButton = {
                    Button(onClick = onFinish) {
                        Text(stringResource(R.string.common_close))
                    }
                },
            )
        }

        ShareFlowStep.Ready -> {
            SecondaryPageScaffold(
                title = stringResource(R.string.share_choose_recipient),
                onBack = onFinish,
            ) { innerPadding, scrollBehavior ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding())
                        .imePadding(),
                ) {
                    ShareSummaryCard(
                        items = sharedItems,
                        mode = shareMode,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (ownOffline) {
                        AppearOfflineNoticeBanner(
                            busy = goingOnline,
                            onGoOnline = {
                                if (!goingOnline) {
                                    goingOnline = true
                                    scope.launch {
                                        repository.setPresence(Presence.Online)
                                        val updated = repository.profile.value
                                        SessionPrefs.saveUserProfile(
                                            context = context,
                                            username = updated.username,
                                            presence = updated.presence,
                                            avatarUri = updated.avatarUri,
                                            statusMessage = updated.statusMessage,
                                            saveDisplayPicture = false,
                                        )
                                        goingOnline = false
                                    }
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    errorMessage?.let { error ->
                        Text(
                            text = localizeUserMessage(error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    CompactSearchField(
                        state = searchState,
                        placeholder = stringResource(R.string.share_search_placeholder),
                        clearContentDescription = stringResource(R.string.share_search_clear),
                        compact = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("share_recipients_list")
                            .selectableGroup()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (evaluatedContacts.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.share_no_contacts),
                                    modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = evaluatedContacts,
                                key = { _, item -> item.contact.id },
                            ) { index, evaluation ->
                                RecipientRow(
                                    evaluation = evaluation,
                                    selected = evaluation.contact.id == selectedContactId,
                                    shape = ListItemDefaults.segmentedShapes(index, evaluatedContacts.size).shape,
                                    onClick = { selectedContactId = evaluation.contact.id },
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (shareMode == ExternalShareMode.Files) {
                                OutlinedTextField(
                                    value = optionalMessage,
                                    onValueChange = { optionalMessage = it },
                                    label = { Text(stringResource(R.string.share_optional_message_label)) },
                                    placeholder = { Text(stringResource(R.string.share_optional_message_placeholder)) },
                                    maxLines = 4,
                                    enabled = !sending,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("share_optional_message_input"),
                                )
                            }
                            val sendLabel = stringResource(
                                if (shareMode == ExternalShareMode.Photos) {
                                    R.string.share_send_photos_action
                                } else {
                                    R.string.share_send_file_action
                                },
                            )
                            Button(
                                onClick = ::sendSelected,
                                enabled = selectedEvaluation != null && !sending,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("share_send_button"),
                            ) {
                                LoadingButtonContent(
                                    loading = sending,
                                    label = stringResource(R.string.share_sending),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(sendLabel)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareSummaryCard(
    items: List<SharedItemInfo>,
    mode: ExternalShareMode,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val totalKnownBytes = remember(items) {
        items.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    }
    val hasTotalSize = remember(items) { items.isNotEmpty() && items.all { it.sizeBytes >= 0L } }
    val sizeLabel = remember(totalKnownBytes, hasTotalSize) {
        if (hasTotalSize) Formatter.formatShortFileSize(context, totalKnownBytes) else null
    }
    val first = items.firstOrNull()
    val titleText = if (items.size <= 1) {
        first?.displayName.orEmpty()
    } else {
        quantityNumberSentenceResource(
            count = items.size,
            oneBeforeRes = R.string.share_photos_count_singular_before_number,
            oneAfterRes = R.string.share_photos_count_singular_after_number,
            otherBeforeRes = R.string.share_photos_count_plural_before_number,
            otherAfterRes = R.string.share_photos_count_plural_after_number,
        )
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (first?.thumbnail != null) {
                Image(
                    bitmap = first.thumbnail,
                    contentDescription = stringResource(R.string.share_preview_image_desc),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (mode == ExternalShareMode.Photos) {
                                Icons.Default.PhotoLibrary
                            } else {
                                Icons.AutoMirrored.Filled.InsertDriveFile
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sizeLabel != null) {
                    Text(
                        text = if (mode == ExternalShareMode.Photos) {
                            stringResource(
                                R.string.share_summary_photos_format,
                                stringResource(R.string.share_option_photos_title),
                                sizeLabel,
                            )
                        } else {
                            sizeLabel
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearOfflineNoticeBanner(
    busy: Boolean,
    onGoOnline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.share_appear_offline_notice_you_singular),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = onGoOnline,
                enabled = !busy,
                modifier = Modifier.testTag("share_go_online_button"),
            ) {
                Text(stringResource(R.string.share_go_online_action))
            }
        }
    }
}

@Composable
private fun RecipientRow(
    evaluation: RecipientEvaluation,
    selected: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
) {
    val contact = evaluation.contact
    val displayName = remember(contact.displayName, contact.email) {
        contact.displayName.ifBlank { contact.email }.messengerPlusPlainText()
    }
    val rowAlpha = if (evaluation.eligible) 1f else 0.55f
    Surface(
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .alpha(rowAlpha)
            .testTag("share_recipient_${contact.id}")
            .clip(shape)
            .selectable(
                selected = selected,
                enabled = evaluation.eligible,
                role = Role.RadioButton,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(
                name = displayName,
                avatarUri = contact.avatarUri,
                presence = contact.presence,
                showPresenceIndicator = true,
                size = 44.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = evaluation.statusNote ?: contact.presence.displayLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (evaluation.eligible) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (evaluation.eligible) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    RadioButton(
                        selected = false,
                        onClick = null,
                    )
                }
            }
        }
    }
}

private fun inspectSharedUri(
    context: Context,
    uri: Uri,
    decodeThumbnail: Boolean,
): SharedItemInfo {
    val displayName = context.queryDocumentDisplayName(uri)
        ?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.share_option_files_title)
    val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val resolverMime = runCatching { context.contentResolver.getType(uri)?.lowercase(Locale.ROOT) }.getOrNull()
    val inferredMime = resolverMime
        ?: extension.takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)?.lowercase(Locale.ROOT) }
    val sizeBytes = queryUriSizeBytes(context, uri)

    val isPicture = (
        inferredMime?.startsWith("image/") == true ||
            extension in IMAGE_EXTENSIONS ||
            canDecodeImageBounds(context, uri)
        )

    val thumbnail = if (isPicture && decodeThumbnail) {
        decodeThumbnailBitmap(context, uri, maxEdgePx = 256)
    } else {
        null
    }

    return SharedItemInfo(
        uri = uri,
        displayName = displayName,
        mimeType = inferredMime,
        sizeBytes = sizeBytes,
        isPicture = isPicture,
        thumbnail = thumbnail,
    )
}

private fun queryUriSizeBytes(context: Context, uri: Uri): Long {
    val queried = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (col >= 0 && cursor.moveToFirst() && !cursor.isNull(col)) cursor.getLong(col) else null
        }
    }.getOrNull()
    if (queried != null && queried >= 0L) return queried
    return runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull()?.takeIf { it >= 0L } ?: -1L
}

private fun canDecodeImageBounds(context: Context, uri: Uri): Boolean = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, options)
        options.outWidth > 0 && options.outHeight > 0
    } ?: false
}.getOrDefault(false)

private fun decodeThumbnailBitmap(context: Context, uri: Uri, maxEdgePx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdgePx * 2) sample *= 2
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val bitmap = orientBitmapIfNeeded(context, uri, decoded)
    if (bitmap !== decoded) decoded.recycle()
    bitmap.asImageBitmap()
}.getOrNull()

private fun orientBitmapIfNeeded(context: Context, uri: Uri, source: Bitmap): Bitmap {
    val orientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            android.media.ExifInterface(input).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrNull() ?: return source
    return source.withExifOrientation(orientation)
}
