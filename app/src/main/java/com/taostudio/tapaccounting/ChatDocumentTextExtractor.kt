package com.taostudio.tapaccounting

import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

object ChatDocumentTextExtractor {

    fun extractDocxText(inputStream: InputStream): String {
        ZipInputStream(inputStream).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.name == "word/document.xml") {
                    val xml = zip.readBytes().decodeToString()
                    val text = extractDocxPlainText(xml)
                    if (text.isNotBlank()) return text
                    throw IOException("Word 文档中没有可读取的文字")
                }
            }
        }
        throw IOException("不是有效的 .docx 文件")
    }

    private fun extractDocxPlainText(xml: String): String {
        val withBreaks = xml.replace(Regex("""</w:p>"""), "</w:p>\n")
        val builder = StringBuilder()
        Regex("""<w:t[^>]*>(.*?)</w:t>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(withBreaks)
            .forEach { match ->
                builder.append(decodeXmlEntities(match.groupValues[1]))
            }
        return builder.toString()
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun decodeXmlEntities(text: String): String {
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
