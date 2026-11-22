package org.aralozkaya.msngr

import java.io.File
import java.io.IOException

internal fun createTestTempDir(prefix: String): File {
    val dir = File.createTempFile(prefix, "")
    if (!dir.delete() || !dir.mkdirs()) {
        throw IOException("Failed to create temp directory: $dir")
    }
    return dir
}
