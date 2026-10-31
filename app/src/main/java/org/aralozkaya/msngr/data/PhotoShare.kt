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
)
data class PhotoShare(
    val id: String,
    val outgoing: Boolean,
    val status: PhotoShareStatus = PhotoShareStatus.Invited,
    val photos: List<SharedPhoto> = emptyList(),
    val selectedId: String? = null,
    val remoteStopped: Boolean = false,
    val error: String? = null,
)
data class PhotoToShare(val name: String, val hash: String, val thumbnail: ByteArray, val preview: ByteArray,
    val originalPath: String? = null)
internal data class OriginalPhoto(val name: String, val path: String, val hash: String)
