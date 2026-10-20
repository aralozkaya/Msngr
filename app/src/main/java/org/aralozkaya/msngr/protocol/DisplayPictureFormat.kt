package org.aralozkaya.msngr.protocol

/** Native WLM 2009's user-tile byte budget. Pixels are independent of the 96px UI thumbnail. */
internal object DisplayPictureLimits {
    const val MAX_BYTES = 199_999 // Native WLM requires strictly less than 200,000 bytes.
    const val MAX_SOURCE_BYTES = 32 * 1024 * 1024
    const val MAX_PIXELS = 1_048_576L
    const val MAX_SOURCE_PIXELS = 16_777_216L
    const val MAX_ANIMATION_PIXELS = 128_000_000L
}

internal enum class DisplayPictureFormat(val mimeType: String, val extension: String) {
    PNG("image/png", "png"), JPEG("image/jpeg", "jpg"), GIF("image/gif", "gif"), BMP("image/bmp", "bmp");

    companion object {
        fun detect(bytes: ByteArray): DisplayPictureFormat? = when {
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)) -> PNG
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> JPEG
            GifDisplayPicture.hasSignature(bytes) -> GIF
            bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> BMP
            else -> null
        }
    }
}
