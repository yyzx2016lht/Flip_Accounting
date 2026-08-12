package com.taostudio.tapaccounting.data.sync

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SharedWebDavClient {
    private val json = "application/json; charset=utf-8".toMediaType()
    private val gzip = "application/gzip".toMediaType()
    private val xml = "text/xml; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val ensuredDirectories = mutableSetOf<String>()

    data class Config(val baseUrl: String, val username: String, val password: String)

    fun ensureDirectory(config: Config, path: String) {
        var current = ""
        segments(path).forEach {
            current += "/$it"
            val key = "${config.baseUrl}|${config.username}|$current"
            if (!ensuredDirectories.add(key)) return@forEach
            execute(config, Request.Builder().url(url(config, current)).method("MKCOL", ByteArray(0).toRequestBody(null)).build(), setOf(200, 201, 204, 301, 302, 405)).close()
        }
    }

    fun put(config: Config, path: String, content: String) {
        val request = Request.Builder().url(url(config, path)).put(content.toRequestBody(json)).build()
        execute(config, request, setOf(200, 201, 204)).close()
    }

    fun putGzip(config: Config, path: String, content: ByteArray) {
        val request = Request.Builder().url(url(config, path)).put(content.toRequestBody(gzip)).build()
        execute(config, request, setOf(200, 201, 204)).close()
    }

    fun get(config: Config, path: String): String {
        val response = execute(config, Request.Builder().url(url(config, path)).get().build(), setOf(200))
        return response.use { it.body?.string() ?: error("云端文件为空") }
    }

    fun getBytes(config: Config, path: String): ByteArray {
        val response = execute(config, Request.Builder().url(url(config, path)).get().build(), setOf(200))
        return response.use { it.body?.bytes() ?: error("云端文件为空") }
    }

    fun exists(config: Config, path: String): Boolean {
        val request = Request.Builder().url(url(config, path)).head().build()
        return withAuth(config, request).let { client.newCall(it).execute() }.use {
            when {
                it.isSuccessful -> true
                it.code == 404 -> false
                else -> error(errorMessage(it.code))
            }
        }
    }

    fun listOperations(config: Config, path: String): List<String> {
        val result = mutableListOf<String>()
        val pending = ArrayDeque<Pair<String, Int>>().apply { add(path.trimEnd('/') to 0) }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val (current, depth) = pending.removeFirst()
            if (!visited.add(current) || depth > 4) continue
            listHrefs(config, current).forEach { href ->
                val relativeToCurrent = href.substringAfter(current.trim('/'), "").trim('/')
                if (relativeToCurrent.isBlank()) return@forEach
                val child = "$current/$relativeToCurrent"
                when {
                    child.endsWith(".json", true) || child.endsWith(".json.gz", true) -> {
                        val relativeToRoot = child.substringAfter(path.trim('/')).trimStart('/')
                        if (result.size < 10_000) result += relativeToRoot
                    }
                    depth < 4 -> pending.add(child to depth + 1)
                }
            }
        }
        return result.distinct()
    }

    private fun listHrefs(config: Config, path: String): List<String> {
        val request = Request.Builder().url(url(config, path)).header("Depth", "1")
            .method("PROPFIND", "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>".toRequestBody(xml)).build()
        val body = execute(config, request, setOf(207)).use { it.body?.string().orEmpty() }
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(body)) }
        val result = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.substringAfter(':').equals("href", true)) {
                result += URLDecoder.decode(parser.nextText(), "UTF-8")
            }
            event = parser.next()
        }
        return result
    }

    private fun execute(config: Config, request: Request, ok: Set<Int>) = client.newCall(withAuth(config, request)).execute().also {
        if (it.code !in ok) {
            val message = errorMessage(it.code)
            it.close(); error(message)
        }
    }

    private fun errorMessage(code: Int) = when (code) {
        401, 403 -> "账号或应用密码错误"
        429 -> "同步请求过于频繁"
        503 -> "坚果云暂时繁忙，请稍后再试"
        else -> "WebDAV HTTP $code"
    }

    private fun withAuth(config: Config, request: Request) = request.newBuilder().header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8)).build()
    private fun url(config: Config, path: String): String = config.baseUrl.trim().let { if (it.startsWith("http")) it else "https://$it" }.trimEnd('/') + "/" + segments(path).joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    private fun segments(path: String) = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
}
