package org.aralozkaya.msngr.protocol

import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

internal fun String.escapeXml(): String = buildString(length) {
    for (character in this@escapeXml) {
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(character)
        }
    }
}

internal fun parseProtocolXml(xml: String, namespaceAware: Boolean = true): Document =
    parseProtocolXml(InputSource(xml.removePrefix("\uFEFF").reader()), namespaceAware)

internal fun parseProtocolXml(bytes: ByteArray, namespaceAware: Boolean = true): Document =
    parseProtocolXml(InputSource(bytes.inputStream()), namespaceAware)

private fun parseProtocolXml(source: InputSource, namespaceAware: Boolean): Document {
    val factory = if (namespaceAware) namespacedFactory else plainFactory
    val builder = synchronized(factory) { factory.newDocumentBuilder() }
    builder.setEntityResolver { _, _ -> throw SAXException("External XML entities are not allowed.") }
    val document = builder.parse(source)
    if (document.doctype != null) throw SAXException("XML document types are not allowed.")
    return document
}

private val namespacedFactory by lazy { protocolXmlFactory(namespaceAware = true) }
private val plainFactory by lazy { protocolXmlFactory(namespaceAware = false) }

private fun protocolXmlFactory(namespaceAware: Boolean) = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = namespaceAware
    isExpandEntityReferences = false
    for ((feature, enabled) in mapOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )) {
        try {
            setFeature(feature, enabled)
        } catch (_: ParserConfigurationException) {
            // Android's DOM parser omits these features and exposes declarations through Document.doctype.
        }
    }
}
