package org.aralozkaya.msngr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val DEFAULT_DISPLAY_PICTURE_URI_PREFIX = "file:///android_asset/display_pictures/"

internal data class DefaultDisplayPicture(
    val id: String,
    val name: String,
    val group: String,
    val file: String,
    val animated: Boolean,
) {
    val uri: String get() = DEFAULT_DISPLAY_PICTURE_URI_PREFIX + file
    val version: String get() = when (group) {
        "MSN/Windows Live Messenger 6.1 to 8.5" -> "6.1–8.5"
        "Windows Live Messenger 2009" -> "2009"
        "Windows Live Messenger 2011 and 2012" -> "2011–2012"
        else -> group
    }
}

internal suspend fun Context.loadDefaultDisplayPictures(): List<DefaultDisplayPicture> = withContext(Dispatchers.IO) {
    val catalog = assets.open("display_pictures/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
    val pictures = catalog.getJSONArray("pictures")
    List(pictures.length()) { index ->
        val entry = pictures.getJSONObject(index)
        DefaultDisplayPicture(entry.getString("id"), entry.getString("name"), entry.getString("group"),
            entry.getString("file"), entry.getBoolean("animated"))
    }
}
