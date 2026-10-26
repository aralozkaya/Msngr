package org.aralozkaya.msngr.protocol

/** A history reference. Original CAB bytes live once in the account's wink store. */
data class WinkReference(val metadata: MsnDisplayPicture, val name: String = "Wink")

class WinkPackage internal constructor(
    private val cabinet: ByteArray,
    val animation: ByteArray,
    val thumbnail: ByteArray?,
    val width: Int,
    val height: Int,
) {
    fun bytes(): ByteArray = cabinet.copyOf()
}

object MsnWink {
    const val OBJECT_TYPE = 8
    const val APP_ID = 1
    const val MAX_CAB_BYTES = 2 * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 8 * 1024 * 1024
    const val MAX_PLAYBACK_MILLIS = 12_000L

    fun parseOffer(xml: String?): MsnDisplayPicture? = MsnDisplayPicture.parseObject(xml, OBJECT_TYPE)?.takeIf {
        it.size in 36..MAX_CAB_BYTES && MsnBase64.decode(it.sha1d)?.size == 20
    }

    fun payloads(metadata: MsnDisplayPicture): List<ByteArray> {
        require(metadata.objectType == OBJECT_TYPE)
        // Native signatures can exceed one switchboard MSG; use MSNP MIME chunking.
        return Msnp18Protocol.inkPayloadsFromEncodedBody(
            Msnp18Protocol.NUDGE_CONTENT_TYPE, "ID: 2\r\nData: ${metadata.toXml()}\r\n\r\n")
    }
}
