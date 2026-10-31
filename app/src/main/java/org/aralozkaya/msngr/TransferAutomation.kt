package org.aralozkaya.msngr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.data.FileTransfer
import org.aralozkaya.msngr.data.FileTransferStatus
import org.aralozkaya.msngr.data.MessengerRepository
import org.aralozkaya.msngr.data.PhotoShareStatus
import org.aralozkaya.msngr.data.SharedPhoto

/** Watches only the live session, never restored history or the currently visible chat. */
internal class TransferAutomation(
    private val settings: (AccountIdentity) -> TransferSettings,
    private val saveFile: suspend (AccountIdentity, FileTransfer) -> Unit,
    private val savePhoto: suspend (AccountIdentity, String, SharedPhoto) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private var job: Job? = null

    fun attach(repository: MessengerRepository) {
        job?.cancel()
        job = scope.launch {
            coroutineScope {
                launch {
                    val accepted = mutableSetOf<String>()
                    val saved = mutableSetOf<String>()
                    repository.liveTransferMessages.collect { conversations ->
                        val account = repository.signedInAccountIdentity ?: return@collect
                        val policy = settings(account)
                        conversations.forEach { (contact, messages) -> messages.forEach messageLoop@ { message ->
                            val transfer = message.fileTransfer ?: return@messageLoop
                            if (!transfer.outgoing && transfer.photoId == null) {
                                val key = "${account.storageKey}:$contact:${transfer.id}"
                                if (policy.autoAccept && transfer.status == FileTransferStatus.Offered && accepted.add(key)) launch {
                                    if (repository.signedInAccountIdentity == account) repository.acceptFile(contact, transfer.id)
                                }
                                if (policy.autoSaveFiles && transfer.status == FileTransferStatus.Complete && saved.add(key)) launch {
                                    if (repository.signedInAccountIdentity == account) saveFile(account, transfer)
                                }
                            }
                        } }
                    }
                }
                launch {
                    val accepted = mutableSetOf<String>()
                    val requested = mutableSetOf<String>()
                    val saved = mutableSetOf<String>()
                    repository.photoShares.collect { shares ->
                        val account = repository.signedInAccountIdentity ?: return@collect
                        val policy = settings(account)
                        shares.forEach { (contact, share) ->
                            val key = "${account.storageKey}:$contact:${share.id}"
                            if (policy.autoAccept && !share.outgoing && share.status == PhotoShareStatus.Invited && accepted.add(key)) launch {
                                if (repository.signedInAccountIdentity == account) repository.acceptPhotos(contact, share.id)
                            }
                            if (policy.autoSaveFiles && policy.autoSavePhotos) share.photos.filterNot { it.outgoing }.forEach { photo ->
                                val photoKey = "$key:${photo.id}"
                                if (photo.originalPath != null && saved.add(photoKey)) launch {
                                    if (repository.signedInAccountIdentity == account) savePhoto(account, share.id, photo)
                                }
                            }
                            // Request originals one at a time so a 32-photo share cannot exhaust
                            // the protocol's eight active transfers per peer.
                            if (policy.autoSaveFiles && policy.autoSavePhotos && share.status == PhotoShareStatus.Active &&
                                share.photos.none { it.originalPending }) {
                                share.photos.firstOrNull { !it.outgoing && it.originalPath == null && it.originalError == null &&
                                    "$key:${it.id}" !in requested }?.let { photo ->
                                    requested.add("$key:${photo.id}")
                                    launch {
                                        if (repository.signedInAccountIdentity == account) repository.requestPhotoOriginal(contact, share.id, photo.id)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
