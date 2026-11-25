package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.protocol.MsnDisplayPicture

enum class PhotoShareStatus { Invited, Active, Stopped, Declined, Failed }
data class SharedPhoto(
    val id: String,
    val name: String,
    val hash: String,
    val thumbnail: MsnDisplayPicture,
    val preview: MsnDisplayPicture,
    val localPath: String? = null,
    val downloadedByPeer: Boolean = false,
    val originalPath: String? = null,
    val originalPending: Boolean = false,
    val originalError: String? = null,
    val originalSentToPeer: Boolean = false,
    val previewError: String? = null,
    val sortIndex: Int? = null,
    val outgoing: Boolean = false,
    val previewSentBytes: Long = 0L,
    val originalSize: Long = 0L,
    val originalTransferredBytes: Long = 0L,
)
data class PhotoShare(
    val id: String,
    val outgoing: Boolean,
    val status: PhotoShareStatus = PhotoShareStatus.Invited,
    val photos: List<SharedPhoto> = emptyList(),
    val selectedId: String? = null,
    val remoteStopped: Boolean = false,
    val error: String? = null,
    val pendingBytes: Long = 0L,
) {
    val totalOutgoingBytes: Long
        get() = if (status == PhotoShareStatus.Invited && outgoing) {
            pendingBytes.coerceAtLeast(0L)
        } else {
            photos.filter { it.outgoing }.sumOf { it.preview.size.toLong() + it.originalSize.coerceAtLeast(0L) }
        }

    val sharedOutgoingBytes: Long
        get() = if (status == PhotoShareStatus.Invited && outgoing) {
            0L
        } else {
            photos.filter { it.outgoing }.sumOf { photo ->
                val previewBytes = if (photo.downloadedByPeer) {
                    photo.preview.size.toLong()
                } else {
                    photo.previewSentBytes.coerceIn(0L, photo.preview.size.toLong())
                }
                val origSize = photo.originalSize.coerceAtLeast(0L)
                val origBytes = if (photo.originalSentToPeer) {
                    origSize
                } else {
                    photo.originalTransferredBytes.coerceIn(0L, origSize)
                }
                previewBytes + origBytes
            }
        }

    val remainingOutgoingBytes: Long
        get() = (totalOutgoingBytes - sharedOutgoingBytes).coerceAtLeast(0L)
}
data class PhotoToShare(val name: String, val hash: String, val thumbnail: ByteArray, val preview: ByteArray,
    val originalPath: String? = null)
internal data class OriginalPhoto(val name: String, val path: String, val hash: String)
