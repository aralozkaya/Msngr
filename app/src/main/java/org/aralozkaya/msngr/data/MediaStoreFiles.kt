package org.aralozkaya.msngr.data

import java.io.File
import java.io.OutputStream

internal fun File.recoverMediaFile(): File {
    val backup = File(parentFile, "$name.bak")
    if (backup.isFile) {
        if (isFile) check(backup.delete()) { "Could not remove an obsolete backup of $name." }
        else check(backup.renameTo(this)) { "Could not restore $name." }
    }
    return this
}

internal fun File.mediaFiles(extension: String): List<File> {
    val entries = if (exists()) checkNotNull(listFiles()) { "Could not read media directory." } else return emptyList()
    return entries.mapNotNull { entry ->
        when {
            entry.extension == extension && entry.isFile -> entry
            entry.name.endsWith(".$extension.bak") && entry.isFile ->
                File(this, entry.nameWithoutExtension).recoverMediaFile()
            entry.name.endsWith(".$extension.tmp") && entry.isFile -> {
                entry.delete()
                null
            }
            else -> null
        }
    }.distinct()
}

/** Callers serialize access to the directory while reading or replacing its files. */
internal fun File.replaceMediaFile(write: (OutputStream) -> Unit) {
    recoverMediaFile()
    val temporary = File(parentFile, "$name.tmp")
    try {
        temporary.outputStream().use { output ->
            write(output)
            output.fd.sync()
        }
        if (temporary.renameTo(this)) return
        // Windows cannot rename over an existing file. Keep the old copy until the new name is installed.
        val backup = File(parentFile, "$name.bak")
        check(isFile && renameTo(backup)) { "Could not replace $name." }
        if (!temporary.renameTo(this)) {
            recoverMediaFile()
            error("Could not save $name.")
        }
        backup.delete()
    } finally {
        temporary.delete()
    }
}
