package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ChatDocumentTextExtractorTest {

    @Test
    fun extractDocxText_readsWordDocumentXml() {
        val docxBytes = buildMinimalDocx(
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    <w:p><w:r><w:t>Hello </w:t><w:t>World</w:t></w:r></w:p>
                    <w:p><w:r><w:t>第二行</w:t></w:r></w:p>
                </w:body>
            </w:document>"""
        )
        val text = ChatDocumentTextExtractor.extractDocxText(ByteArrayInputStream(docxBytes))
        assertTrue(text.contains("Hello World"))
        assertTrue(text.contains("第二行"))
    }

    private fun buildMinimalDocx(documentXml: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
