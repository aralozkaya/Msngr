package org.aralozkaya.msngr.protocol

/** Small platform-independent Base64 codec so MSNP objects also work on Android 7. */
internal object MsnBase64 {
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val result = StringBuilder(((bytes.size + 2) / 3) * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 0xff
            val hasSecond = index < bytes.size
            val second = if (hasSecond) bytes[index++].toInt() and 0xff else 0
            val hasThird = index < bytes.size
            val third = if (hasThird) bytes[index++].toInt() and 0xff else 0
            val value = (first shl 16) or (second shl 8) or third
            result.append(ALPHABET[(value ushr 18) and 0x3f])
            result.append(ALPHABET[(value ushr 12) and 0x3f])
            result.append(if (hasSecond) ALPHABET[(value ushr 6) and 0x3f] else '=')
            result.append(if (hasThird) ALPHABET[value and 0x3f] else '=')
        }
        return result.toString()
    }

    fun decode(value: String): ByteArray? {
        val input = value.filterNot(Char::isWhitespace)
        if (input.isEmpty()) return byteArrayOf()
        if (input.length % 4 != 0) return null
        val padding = when {
            input.endsWith("==") -> 2
            input.endsWith('=') -> 1
            else -> 0
        }
        if ('=' in input.dropLast(padding)) return null
        val result = ByteArray((input.length / 4) * 3 - padding)
        var inputIndex = 0
        var outputIndex = 0
        while (inputIndex < input.length) {
            val first = input[inputIndex++].base64Value() ?: return null
            val second = input[inputIndex++].base64Value() ?: return null
            val thirdCharacter = input[inputIndex++]
            val fourthCharacter = input[inputIndex++]
            val third = if (thirdCharacter == '=') 0 else thirdCharacter.base64Value() ?: return null
            val fourth = if (fourthCharacter == '=') 0 else fourthCharacter.base64Value() ?: return null
            val decoded = (first shl 18) or (second shl 12) or (third shl 6) or fourth
            if (outputIndex < result.size) result[outputIndex++] = (decoded ushr 16).toByte()
            if (outputIndex < result.size) result[outputIndex++] = (decoded ushr 8).toByte()
            if (outputIndex < result.size) result[outputIndex++] = decoded.toByte()
        }
        return result
    }

    private fun Char.base64Value(): Int? {
        val index = ALPHABET.indexOf(this)
        return index.takeIf { it >= 0 }
    }
}
