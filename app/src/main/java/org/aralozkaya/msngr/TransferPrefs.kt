package org.aralozkaya.msngr

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TransferSettings(
    val autoAccept: Boolean = false,
    val autoSaveFiles: Boolean = false,
    val autoSavePhotos: Boolean = false,
    val fileTree: String? = null,
    val fileTreeName: String? = null,
    val photoTree: String? = null,
    val photoTreeName: String? = null,
)

internal object TransferPrefs {
    private const val NAME = "msngr_transfers"
    private val settings = MutableStateFlow(TransferSettings())

    fun state(context: Context) = settings.asStateFlow().also { reload(context) }

    fun read(context: Context, account: AccountIdentity? = SessionPrefs.preferenceAccount(context)): TransferSettings {
        val prefs = AccountPreferences.forAccount(context, NAME, account)
        return TransferSettings(prefs.getBoolean("auto_accept", false), prefs.getBoolean("auto_save_files", false),
            prefs.getBoolean("auto_save_photos", false), prefs.getString("file_tree", null), prefs.getString("file_tree_name", null),
            prefs.getString("photo_tree", null), prefs.getString("photo_tree_name", null))
    }

    fun update(context: Context, transform: (TransferSettings) -> TransferSettings) {
        val value = transform(read(context))
        AccountPreferences.preferences(context, NAME).edit {
            putBoolean("auto_accept", value.autoAccept)
            putBoolean("auto_save_files", value.autoSaveFiles)
            putBoolean("auto_save_photos", value.autoSavePhotos)
            putString("file_tree", value.fileTree)
            putString("file_tree_name", value.fileTreeName)
            putString("photo_tree", value.photoTree)
            putString("photo_tree_name", value.photoTreeName)
        }
        settings.value = value
    }

    internal fun reload(context: Context) { settings.value = read(context) }

    fun treeName(context: Context, uri: Uri): String = runCatching {
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: "Selected folder"
}
