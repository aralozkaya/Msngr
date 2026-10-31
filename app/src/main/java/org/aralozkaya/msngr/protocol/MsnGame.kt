package org.aralozkaya.msngr.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MsnGameInviteContext(
    val entryId: Int,
    val kids: Int,
    val appName: String,
) {
    /**
     * WLM's `FormatP4Context` (`0x005e2224`) formats `"%d;%s;%s"` where `%s` is `"1"` if `kids != 0` else `"0"`,
     * and `CP4EUF::SendInvitation` (`0x005df2c2`) encodes `wcslen(ctx) * 2` UTF-16LE bytes without a trailing NUL.
     */
    fun encode(): ByteArray {
        val flag = if (kids != 0) "1" else "0"
        return "$entryId;$flag;$appName".toByteArray(Charsets.UTF_16LE)
    }

    companion object {
        fun parse(bytes: ByteArray): MsnGameInviteContext? {
            if (bytes.isEmpty() || bytes.size > 2048 || bytes.size % 2 != 0) return null
            val text = bytes.toString(Charsets.UTF_16LE).trimEnd('\u0000')
            val parts = text.split(';', limit = 3)
            if (parts.size < 3) return null
            val entryId = parts[0].trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
            val kids = parts[1].trim().toIntOrNull() ?: 0
            val appName = parts[2].trim().takeIf { it.isNotEmpty() && it.length <= 256 } ?: return null
            return MsnGameInviteContext(entryId = entryId, kids = kids, appName = appName)
        }
    }
}

sealed interface P4Packet {
    data object RemoteAppLoaded : P4Packet
    data class RemoteCancelFile(val cookie: Int) : P4Packet
    data class FileCookie(val cookie: Int) : P4Packet
    data class Data(val value: Any?) : P4Packet {
        val textValue: String
            get() = when (value) {
                null -> ""
                is String -> value
                is Boolean -> if (value) "true" else "false"
                else -> value.toString()
            }
    }
}

/**
 * Binary wire framing for WLM P4 (`CP4Channel` over MSNP2P, `EUF-GUID` `{6A13AF9C-5308-4F35-923A-67E8DDA40C2F}`).
 */
object MsnGameProtocol {
    const val EUF_GUID = "6A13AF9C-5308-4F35-923A-67E8DDA40C2F"
    const val DEFAULT_APP_ID = 6
    const val MAX_DATA_BYTES = 65_536

    private const val HEADER_INTERNAL: Byte = 0x00
    private const val HEADER_APP_DATA: Byte = 0x80.toByte()

    const val INTERNAL_MSG_REMOTE_APP_LOADED: Short = 1
    const val INTERNAL_MSG_REMOTE_CANCEL_FILE: Short = 2
    const val INTERNAL_MSG_FILE_COOKIE: Short = 3

    private const val VT_EMPTY: Short = 0
    private const val VT_NULL: Short = 1
    private const val VT_I2: Short = 2
    private const val VT_I4: Short = 3
    private const val VT_R4: Short = 4
    private const val VT_R8: Short = 5
    private const val VT_BSTR: Short = 8
    private const val VT_ERROR: Short = 10
    private const val VT_BOOL: Short = 11
    private const val VT_I1: Short = 16
    private const val VT_UI1: Short = 17
    private const val VT_UI2: Short = 18
    private const val VT_UI4: Short = 19
    private const val VT_I8: Short = 20
    private const val VT_UI8: Short = 21
    private const val VT_INT: Short = 22
    private const val VT_UINT: Short = 23

    fun appIdForEntry(entryId: Int): Int = if (entryId != 0) entryId else DEFAULT_APP_ID

    /** 4-byte internal control message `00 00 01 00` (`REMOTE APP LOADED`). */
    fun encodeRemoteAppLoaded(): ByteArray =
        byteArrayOf(HEADER_INTERNAL, 0x00, 0x01, 0x00)

    /**
     * Encodes an application payload with the 4-byte P4 header (`80 00 00 00`) followed by
     * ATL `CComVariant::WriteToStream` (`0x00588636`) / `CComBSTR::WriteToStream` (`0x00587d1c`).
     */
    fun encodeData(value: Any?): ByteArray {
        return when (value) {
            null -> ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .put(HEADER_APP_DATA).put(0).putShort(0)
                .putShort(VT_EMPTY)
                .array()
            is String -> {
                val utf16 = value.toByteArray(Charsets.UTF_16LE)
                val cbByteLen = utf16.size + 2 // Includes 2-byte UTF-16LE NUL terminator (0x0000)
                require(cbByteLen <= MAX_DATA_BYTES) { "Game data exceeds the 64 KB P4 packet limit." }
                ByteBuffer.allocate(4 + 2 + 4 + cbByteLen).order(ByteOrder.LITTLE_ENDIAN)
                    .put(HEADER_APP_DATA).put(0).putShort(0)
                    .putShort(VT_BSTR)
                    .putInt(cbByteLen)
                    .put(utf16)
                    .putShort(0)
                    .array()
            }
            is Int -> ByteBuffer.allocate(4 + 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
                .put(HEADER_APP_DATA).put(0).putShort(0)
                .putShort(VT_I4)
                .putInt(value)
                .array()
            is Short -> ByteBuffer.allocate(4 + 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
                .put(HEADER_APP_DATA).put(0).putShort(0)
                .putShort(VT_I2)
                .putShort(value)
                .array()
            is Boolean -> ByteBuffer.allocate(4 + 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
                .put(HEADER_APP_DATA).put(0).putShort(0)
                .putShort(VT_BOOL)
                .putShort(if (value) (-1).toShort() else 0)
                .array()
            is Double -> ByteBuffer.allocate(4 + 2 + 8).order(ByteOrder.LITTLE_ENDIAN)
                .put(HEADER_APP_DATA).put(0).putShort(0)
                .putShort(VT_R8)
                .putDouble(value)
                .array()
            else -> encodeData(value.toString())
        }
    }

    /**
     * Decodes a P4 binary packet (`CP4Channel::OnReceived` at `0x005da67e` and
     * `CComVariant::ReadFromStream` at `0x005d8777`).
     */
    fun decodePacket(bytes: ByteArray): P4Packet? {
        if (bytes.size < 4 || bytes.size > MAX_DATA_BYTES + 16) return null
        val first = bytes[0].toInt() and 0xFF
        val version = first and 0x7F
        if (version != 0) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val isAppData = (first and 0x80) != 0
        if (!isAppData) {
            val msgType = buffer.getShort(2)
            return when (msgType) {
                INTERNAL_MSG_REMOTE_APP_LOADED -> P4Packet.RemoteAppLoaded
                INTERNAL_MSG_REMOTE_CANCEL_FILE -> {
                    if (bytes.size < 8) null else P4Packet.RemoteCancelFile(buffer.getInt(4))
                }
                INTERNAL_MSG_FILE_COOKIE -> {
                    if (bytes.size < 8) null else P4Packet.FileCookie(buffer.getInt(4))
                }
                else -> null
            }
        }
        if (bytes.size < 6) return null
        return when (val vt = buffer.getShort(4)) {
            VT_EMPTY, VT_NULL -> P4Packet.Data("")
            VT_BSTR -> {
                if (bytes.size < 10) return null
                val cbByteLen = buffer.getInt(6)
                if (cbByteLen == 0) return P4Packet.Data("")
                if (cbByteLen < 0 || cbByteLen > MAX_DATA_BYTES || cbByteLen % 2 != 0 || 10 + cbByteLen > bytes.size) {
                    return null
                }
                val contentBytes = (cbByteLen - 2).coerceAtLeast(0)
                val str = String(bytes, 10, contentBytes, Charsets.UTF_16LE).trimEnd('\u0000')
                P4Packet.Data(str)
            }
            VT_I1, VT_UI1 -> {
                if (bytes.size < 7) null else P4Packet.Data(bytes[6].toInt())
            }
            VT_I2, VT_UI2 -> {
                if (bytes.size < 8) null else P4Packet.Data(buffer.getShort(6).toInt())
            }
            VT_BOOL -> {
                if (bytes.size < 8) null else P4Packet.Data(buffer.getShort(6).toInt() != 0)
            }
            VT_I4, VT_UI4, VT_INT, VT_UINT, VT_ERROR -> {
                if (bytes.size < 10) null else P4Packet.Data(buffer.getInt(6))
            }
            VT_R4 -> {
                if (bytes.size < 10) null else P4Packet.Data(buffer.getFloat(6).toDouble())
            }
            VT_R8 -> {
                if (bytes.size < 14) null else P4Packet.Data(buffer.getDouble(6))
            }
            VT_I8, VT_UI8 -> {
                if (bytes.size < 14) null else P4Packet.Data(buffer.getLong(6))
            }
            else -> null
        }
    }
}
