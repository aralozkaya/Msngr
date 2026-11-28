package org.aralozkaya.msngr.data

import org.aralozkaya.msngr.readBytesBounded
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal const val MESSENGER_USER_AGENT = "MSNMSGR/14.0.8117.0416"
private const val MAX_SERVICE_BYTES = 16 * 1024 * 1024
private const val MAX_REDIRECTS = 10
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

internal fun InputStream.readServiceText(maxBytes: Int = MAX_SERVICE_BYTES): String =
    readBytesBounded(maxBytes) { "Messenger service response exceeds $maxBytes bytes." }.toString(Charsets.UTF_8)

internal data class SoapResponse(val status: Int, val body: String?, val bodyReadFailure: Exception? = null)

internal class SoapExchangeException(cause: IOException) : IOException(cause.message, cause)

internal fun URL.canRepeatSoapAt(next: URL): Boolean {
    if (next.userInfo != null || !next.host.equals(host, ignoreCase = true)) return false
    val fromPort = if (port == -1) defaultPort else port
    val toPort = if (next.port == -1) next.defaultPort else next.port
    return (protocol == next.protocol && fromPort == toPort) ||
        (protocol == "http" && fromPort == 80 && next.protocol == "https" && toPort == 443)
}

internal fun postSoapEnvelope(
    endpoint: String,
    action: String,
    envelope: String,
    timeoutMillis: Int,
    maxResponseBytes: Int = MAX_SERVICE_BYTES,
    followRedirects: Boolean = true,
): SoapResponse {
    var url = URL(endpoint)
    var post = true
    var requestMayHaveBeenSent = false
    val bytes = envelope.toByteArray(Charsets.UTF_8)
    repeat(MAX_REDIRECTS + 1) {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.setRequestProperty("User-Agent", MESSENGER_USER_AGENT)
            if (post) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                connection.setRequestProperty("SOAPAction", action)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.connect()
                requestMayHaveBeenSent = true
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val location = connection.getHeaderField("Location")
            if (!followRedirects || location == null || status !in REDIRECT_CODES) {
                if (status in 200..299) {
                    return SoapResponse(status, connection.inputStream.use { it.readServiceText(maxResponseBytes) })
                }
                return try {
                    SoapResponse(status, connection.errorStream?.use { it.readServiceText(maxResponseBytes) })
                } catch (error: IOException) {
                    SoapResponse(status, null, error)
                } catch (tooLarge: IllegalArgumentException) {
                    SoapResponse(status, null, tooLarge)
                }
            }

            val next = URL(url, location)
            check(next.protocol == "https" || (next.protocol == "http" && url.protocol == "http")) {
                "The Messenger service returned an unsafe redirect."
            }
            post = post && (status == 307 || status == 308)
            // A 307 or 308 repeats the envelope, which can contain a password or service ticket.
            check(!post || url.canRepeatSoapAt(next)) { "The Messenger service redirected a SOAP request to another origin." }
            url = next
        } catch (error: IOException) {
            if (requestMayHaveBeenSent) throw SoapExchangeException(error)
            throw error
        } finally {
            connection.disconnect()
        }
    }
    error("The Messenger service redirected the request too many times.")
}
