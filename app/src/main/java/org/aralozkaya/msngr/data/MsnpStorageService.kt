package org.aralozkaya.msngr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.protocol.MsnBase64
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

internal data class StorageProfile(
    val profileResourceId: String? = null,
    val expressionProfileResourceId: String? = null,
    val photoResourceId: String? = null,
    val displayName: String? = null,
    val personalStatus: String? = null,
    val displayPictureUrl: String? = null,
)

/** MSNP18-era roaming profile and UserTileStatic storage operations. */
internal object MsnpStorageService {
    private const val NAMESPACE = "http://www.msn.com/webservices/storage/2008"
    private const val ENDPOINT_PATH = "/storageservice/SchematizedStore.asmx"
    private const val TIMEOUT_MILLIS = 15_000

    suspend fun fetchProfile(host: String, ticket: String): StorageProfile =
        post(host, "GetProfile", buildGetProfileRequest(ticket)).let(::parseProfile)

    suspend fun updateDisplayPicture(
        host: String,
        ticket: String,
        photoResourceId: String,
        image: ByteArray,
    ) {
        require(photoResourceId.isNotBlank()) { "The storage profile has no photo resource ID." }
        require(image.isNotEmpty()) { "The display picture is empty." }
        post(
            host,
            "UpdateDocument",
            buildUpdateDocumentRequest(ticket, photoResourceId, image),
        )
    }

    suspend fun createDisplayPicture(
        host: String,
        ticket: String,
        profileResourceId: String,
        image: ByteArray,
    ): String {
        require(profileResourceId.isNotBlank()) {
            "The storage profile has no profile resource ID."
        }
        require(image.isNotEmpty()) { "The display picture is empty." }
        val response = post(
            host,
            "CreateDocument",
            buildCreateDocumentRequest(ticket, profileResourceId, image),
        )
        return parseCreatedDocumentResourceId(response)
    }

    suspend fun removeDisplayPicture(
        host: String,
        ticket: String,
        expressionProfileResourceId: String,
        photoResourceId: String,
    ) {
        require(expressionProfileResourceId.isNotBlank()) {
            "The storage profile has no expression profile resource ID."
        }
        require(photoResourceId.isNotBlank()) { "The storage profile has no photo resource ID." }
        post(
            host,
            "DeleteRelationships",
            buildDeletePhotoRelationshipRequest(
                ticket,
                expressionProfileResourceId,
                photoResourceId,
            ),
        )
    }

    internal fun buildGetProfileRequest(ticket: String): String = envelope(ticket) {
        """
        <GetProfile xmlns="$NAMESPACE">
          <profileHandle>
            <Alias><NameSpace>MyCidStuff</NameSpace><Name>0</Name></Alias>
            <RelationshipName>MyProfile</RelationshipName>
          </profileHandle>
          <profileAttributes>
            <ResourceID>true</ResourceID>
            <DateModified>true</DateModified>
            <ExpressionProfileAttributes>
              <ResourceID>true</ResourceID><DateModified>true</DateModified>
              <DisplayName>true</DisplayName><DisplayNameLastModified>true</DisplayNameLastModified>
              <PersonalStatus>true</PersonalStatus><PersonalStatusLastModified>true</PersonalStatusLastModified>
              <StaticUserTilePublicURL>true</StaticUserTilePublicURL>
              <Photo>true</Photo>
            </ExpressionProfileAttributes>
          </profileAttributes>
        </GetProfile>
        """.trimIndent()
    }

    internal fun buildUpdateDocumentRequest(
        ticket: String,
        photoResourceId: String,
        image: ByteArray,
    ): String = envelope(ticket) {
        val data = MsnBase64.encode(image)
        """
        <UpdateDocument xmlns="$NAMESPACE">
          <document xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="Photo">
            <ResourceID>${photoResourceId.storageEscapeXml()}</ResourceID>
            <Name>Msngr Android display picture</Name>
            <DocumentStreams>
              <DocumentStream xsi:type="PhotoStream">
                <DataSize>0</DataSize>
                <MimeType>image/png</MimeType>
                <DocumentStreamType>UserTileStatic</DocumentStreamType>
                <Data>$data</Data>
              </DocumentStream>
            </DocumentStreams>
          </document>
        </UpdateDocument>
        """.trimIndent()
    }

    internal fun buildCreateDocumentRequest(
        ticket: String,
        profileResourceId: String,
        image: ByteArray,
    ): String = envelope(ticket) {
        val data = MsnBase64.encode(image)
        val cid = profileResourceId.substringBefore('!')
        require(cid.isNotBlank()) { "The storage profile resource ID is invalid." }
        """
        <CreateDocument xmlns="$NAMESPACE">
          <parentHandle>
            <Alias><NameSpace>MyCidStuff</NameSpace><Name>${cid.storageEscapeXml()}</Name></Alias>
            <RelationshipName>/MyProfile/ExpressionProfile</RelationshipName>
          </parentHandle>
          <document xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="Photo">
            <Name>Msngr Android display picture</Name>
            <DocumentStreams>
              <DocumentStream xsi:type="PhotoStream">
                <DataSize>0</DataSize>
                <MimeType>image/png</MimeType>
                <DocumentStreamType>UserTileStatic</DocumentStreamType>
                <Data>$data</Data>
              </DocumentStream>
            </DocumentStreams>
          </document>
          <relationshipName>ProfilePhoto</relationshipName>
        </CreateDocument>
        """.trimIndent()
    }

    internal fun buildDeletePhotoRelationshipRequest(
        ticket: String,
        expressionProfileResourceId: String,
        photoResourceId: String,
    ): String = envelope(ticket) {
        """
        <DeleteRelationships xmlns="$NAMESPACE">
          <sourceHandle>
            <ResourceID>${expressionProfileResourceId.storageEscapeXml()}</ResourceID>
          </sourceHandle>
          <targetHandles>
            <ObjectHandle><ResourceID>${photoResourceId.storageEscapeXml()}</ResourceID></ObjectHandle>
          </targetHandles>
        </DeleteRelationships>
        """.trimIndent()
    }

    internal fun parseCreatedDocumentResourceId(xml: String): String {
        val document = storageDocumentBuilderFactory().newDocumentBuilder()
            .parse(xml.byteInputStream(StandardCharsets.UTF_8))
        return document.getElementsByTagNameNS("*", "CreateDocumentResult")
            .item(0)
            ?.textContent
            .orEmpty()
            .trim()
            .ifBlank {
                throw LiveMsnpStorageException(
                    "The storage service did not return a photo resource ID.",
                )
            }
    }

    internal fun parseProfile(xml: String): StorageProfile {
        val document = storageDocumentBuilderFactory().newDocumentBuilder()
            .parse(xml.byteInputStream(StandardCharsets.UTF_8))
        val result = document.getElementsByTagNameNS("*", "GetProfileResult")
            .item(0) as? Element
            ?: throw LiveMsnpStorageException("The storage service returned an invalid profile.")
        val expression = result.getElementsByTagNameNS("*", "ExpressionProfile")
            .item(0) as? Element
        val photo = expression?.getElementsByTagNameNS("*", "Photo")?.item(0) as? Element
        val staticStream = photo?.getElementsByTagNameNS("*", "DocumentStream")
            ?.let { streams ->
                (0 until streams.length)
                    .mapNotNull { streams.item(it) as? Element }
                    .firstOrNull {
                        it.storageFirstText("DocumentStreamType") == "UserTileStatic" ||
                            it.storageFirstText("DocumentStreamName") == "UserTileStatic"
                    }
            }
        return StorageProfile(
            profileResourceId = result.storageFirstText("ResourceID").ifBlank { null },
            expressionProfileResourceId = expression?.storageFirstText("ResourceID")
                ?.ifBlank { null },
            photoResourceId = photo?.storageFirstText("ResourceID")?.ifBlank { null },
            displayName = expression?.storageDirectText("DisplayName")?.ifBlank { null },
            personalStatus = expression?.storageDirectText("PersonalStatus")?.ifBlank { null },
            displayPictureUrl = staticStream?.storageFirstText("PreAuthURL")?.ifBlank { null },
        )
    }

    private suspend fun post(host: String, action: String, body: String): String =
        withContext(Dispatchers.IO) {
            val connection = (URL(
                "https://${host.storageNormalizedHost()}$ENDPOINT_PATH",
            ).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                instanceFollowRedirects = true
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                setRequestProperty("SOAPAction", "\"$NAMESPACE/$action\"")
                setRequestProperty("User-Agent", "MSNMSGR/14.0.8117.0416")
            }
            try {
                connection.outputStream.use {
                    it.write(body.toByteArray(StandardCharsets.UTF_8))
                }
                val status = connection.responseCode
                val response = (if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                })?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val fault = runCatching {
                        val document = storageDocumentBuilderFactory().newDocumentBuilder()
                            .parse(response.byteInputStream(StandardCharsets.UTF_8))
                        document.documentElement.storageFirstText("faultstring")
                    }.getOrDefault("")
                    throw LiveMsnpStorageException(
                        fault.ifBlank { "Storage $action failed with HTTP $status." },
                    )
                }
                response
            } catch (error: LiveMsnpStorageException) {
                throw error
            } catch (error: Exception) {
                throw LiveMsnpStorageException("Could not complete storage $action.", error)
            } finally {
                connection.disconnect()
            }
        }

    private fun envelope(ticket: String, operation: () -> String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
          <soap:Header>
            <StorageUserHeader xmlns="$NAMESPACE">
              <Puid>0</Puid><TicketToken>${ticket.storageEscapeXml()}</TicketToken>
            </StorageUserHeader>
          </soap:Header>
          <soap:Body>${operation()}</soap:Body>
        </soap:Envelope>
    """.trimIndent().trimStart()
}

private class LiveMsnpStorageException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

private fun String.storageNormalizedHost(): String = trim()
    .removePrefix("https://")
    .removePrefix("http://")
    .substringBefore('/')

private fun String.storageEscapeXml(): String = buildString(length) {
    this@storageEscapeXml.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> character
            },
        )
    }
}

private fun Element.storageFirstText(localName: String): String =
    getElementsByTagNameNS("*", localName).item(0)?.textContent.orEmpty().trim()

private fun Element.storageDirectText(localName: String): String =
    (0 until childNodes.length)
        .map { childNodes.item(it) }
        .firstOrNull { it.localName == localName }
        ?.textContent
        .orEmpty()
        .trim()

private fun storageDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
