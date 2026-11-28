package org.aralozkaya.msngr

import java.security.MessageDigest

internal fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).toHexString()

internal fun String.sha256Hex(): String = toByteArray(Charsets.UTF_8).sha256Hex()
