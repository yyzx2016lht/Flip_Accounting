package com.taostudio.tapaccounting.data.backup

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

internal data class WebDavResource(
    val href: String,
    val decodedPath: String,
    val isCollection: Boolean,
    val contentLength: Long?,
    val lastModified: String?
) {
    val name: String
        get() = decodedPath.trimEnd('/').substringAfterLast('/')
}

internal object WebDavXml {
    fun parseResources(xml: String): List<WebDavResource> {
        if (xml.isBlank()) return emptyList()
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) &&
            !xml.contains("<!ENTITY", ignoreCase = true)) {
            "WebDAV XML must not contain a document type or entity declaration"
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureIfSupported("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val document = ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)).use {
            factory.newDocumentBuilder().parse(it)
        }
        val responses = document.getElementsByTagNameNS("*", "response")
        return buildList {
            for (index in 0 until responses.length) {
                val response = responses.item(index) as? Element ?: continue
                val href = response.firstText("href")?.trim().orEmpty()
                if (href.isBlank()) continue
                val length = response.firstText("getcontentlength")
                    ?.trim()
                    ?.toLongOrNull()
                add(
                    WebDavResource(
                        href = href,
                        decodedPath = decodePath(href),
                        isCollection = response.getElementsByTagNameNS("*", "collection").length > 0,
                        contentLength = length,
                        lastModified = response.firstText("getlastmodified")?.trim()
                    )
                )
            }
        }
    }

    fun decodePath(href: String): String {
        val rawPath = runCatching { URI(href).rawPath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: href.substringBefore('?').substringBefore('#')
        // URLDecoder follows form rules and would otherwise turn a literal '+'
        // in a WebDAV path into a space.
        return URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
    }

    private fun Element.firstText(localName: String): String? {
        val nodes = getElementsByTagNameNS("*", localName)
        return if (nodes.length == 0) null else nodes.item(0)?.textContent
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }
}
