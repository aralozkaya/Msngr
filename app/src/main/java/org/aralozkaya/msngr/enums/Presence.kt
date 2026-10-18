package org.aralozkaya.msngr.enums

/** MSNP presence states exposed by the app. */
enum class Presence(
    val label: String,
    val msnpCode: String,
    val priority: Int,
    val isOnline: Boolean,
) {
    Online("Online", "NLN", 0, true),
    Busy("Busy", "BSY", 1, true),
    Away("Away", "AWY", 2, true),
    Offline("Offline", "HDN", 3, false);

    fun ownStatusLabel(): String = if (this == Offline) "Appear offline" else label

    companion object {
        fun fromStorageValue(value: String): Presence = when (value) {
            "Available" -> Online // migration from early builds
            else -> entries.firstOrNull { it.name == value } ?: Online
        }

        fun fromMsnpCode(value: String): Presence = when (value.uppercase()) {
            "NLN" -> Online
            "BSY" -> Busy
            "IDL", "BRB", "AWY", "PHN", "LUN" -> Away
            "HDN", "FLN" -> Offline
            else -> Offline
        }
    }
}
