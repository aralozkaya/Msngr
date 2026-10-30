package org.aralozkaya.msngr.data

enum class FileTransferStatus { Offered, Transferring, Complete, Declined, Cancelled, Failed }

data class FileTransfer(
    val id: String,
    val name: String,
    val size: Long,
    val outgoing: Boolean,
    val status: FileTransferStatus = FileTransferStatus.Offered,
    val transferred: Long = 0,
    val localPath: String? = null,
    val error: String? = null,
    val photoId: String? = null,
) {
    val active: Boolean get() = status == FileTransferStatus.Offered || status == FileTransferStatus.Transferring
    fun interrupted(): FileTransfer = if (active) copy(status = FileTransferStatus.Failed,
        error = "File transfer was interrupted.") else this
}
