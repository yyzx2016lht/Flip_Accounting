package com.taostudio.tapaccounting.data.backup

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class CloudBackupConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    val remoteDir: String,
    val deviceName: String
)

data class CloudBackupEntry(
    val name: String,
    val mode: String,
    val timestamp: String
)

object WebDavClient {
    private val octet = "application/octet-stream".toMediaType()
    private val xml = "text/xml; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun testConnection(config: CloudBackupConfig) {
        val auth = Credentials.basic(config.username, config.password, Charsets.UTF_8)
        val request = Request.Builder()
            .url(normalizeBaseUrl(config.baseUrl))
            .header("Authorization", auth)
            .header("Depth", "0")
            .method("PROPFIND", "<d:propfind xmlns:d=\"DAV:\"/>".toRequestBody(xml))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code !in setOf(207, 401, 403)) {
                throw IllegalStateException("连接失败：HTTP ${response.code}")
            }
            if (response.code == 401 || response.code == 403) {
                throw IllegalStateException("认证失败：请检查账号或应用密码")
            }
        }
    }

    fun uploadBackup(config: CloudBackupConfig, fileName: String, bytes: ByteArray) {
        val auth = Credentials.basic(config.username, config.password, Charsets.UTF_8)
        val segments = dirSegments(config)
        ensureDirectory(config.baseUrl, auth, segments)

        val uploadUrl = buildUrl(config.baseUrl, *(segments + fileName).toTypedArray())
        val put = Request.Builder()
            .url(uploadUrl)
            .header("Authorization", auth)
            .put(bytes.toRequestBody(octet))
            .build()
        client.newCall(put).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("上传失败：HTTP ${response.code}")
            }
        }
    }

    fun findLatestBackup(config: CloudBackupConfig): CloudBackupEntry? {
        val entries = listBackups(config)
        return entries.maxWithOrNull(compareBy<CloudBackupEntry> { it.timestamp }.thenBy { it.name })
    }

    fun downloadBackup(config: CloudBackupConfig, entry: CloudBackupEntry): ByteArray {
        val auth = Credentials.basic(config.username, config.password, Charsets.UTF_8)
        val segments = dirSegments(config)
        val url = buildUrl(config.baseUrl, *(segments + entry.name).toTypedArray())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败：HTTP ${response.code}")
            }
            return response.body?.bytes() ?: throw IllegalStateException("下载失败：返回内容为空")
        }
    }

    fun cleanupBackups(config: CloudBackupConfig, keepLite: Int = 10, keepFull: Int = 3) {
        val auth = Credentials.basic(config.username, config.password, Charsets.UTF_8)
        val entries = listBackups(config)
        val lite = entries.filter { it.mode == "lite" }.sortedByDescending { it.timestamp }
        val fullLike = entries.filter { it.mode != "lite" }.sortedByDescending { it.timestamp }
        val toDelete = lite.drop(keepLite) + fullLike.drop(keepFull)
        val segments = dirSegments(config)
        toDelete.forEach { entry ->
            val url = buildUrl(config.baseUrl, *(segments + entry.name).toTypedArray())
            val request = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .delete()
                .build()
            client.newCall(request).execute().close()
        }
    }

    private fun listBackups(config: CloudBackupConfig): List<CloudBackupEntry> {
        val auth = Credentials.basic(config.username, config.password, Charsets.UTF_8)
        val segments = dirSegments(config)
        ensureDirectory(config.baseUrl, auth, segments)
        val url = buildUrl(config.baseUrl, *segments.toTypedArray())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("Depth", "1")
            .method("PROPFIND", "<d:propfind xmlns:d=\"DAV:\"/>".toRequestBody(xml))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 207) {
                throw IllegalStateException("读取云端目录失败：HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            val hrefRegex = Regex("<(?:d:)?href>(.*?)</(?:d:)?href>", RegexOption.IGNORE_CASE)
            return hrefRegex.findAll(body)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .map { URLDecoder.decode(it, "UTF-8") }
                .mapNotNull { href ->
                    val name = href.substringAfterLast('/').trim()
                    if (!name.endsWith(".bak", ignoreCase = true)) return@mapNotNull null
                    val mode = Regex("_(lite|full|custom)_\\d{8}_\\d{6}\\.bak$", RegexOption.IGNORE_CASE)
                        .find(name)?.groupValues?.get(1)?.lowercase() ?: "custom"
                    val ts = Regex("_(\\d{8}_\\d{6})\\.bak$", RegexOption.IGNORE_CASE)
                        .find(name)?.groupValues?.get(1) ?: ""
                    if (ts.isBlank()) return@mapNotNull null
                    CloudBackupEntry(name = name, mode = mode, timestamp = ts)
                }
                .toList()
        }
    }

    private fun ensureDirectory(baseUrl: String, auth: String, segments: List<String>) {
        var created = emptyList<String>()
        segments.forEach { segment ->
            created = created + segment
            val url = buildUrl(baseUrl, *created.toTypedArray())
            val request = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .method("MKCOL", ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code !in setOf(201, 200, 204, 301, 302, 405)) {
                    throw IllegalStateException("创建云端目录失败：HTTP ${response.code}")
                }
            }
        }
    }

    private fun dirSegments(config: CloudBackupConfig): List<String> {
        val remoteSegments = config.remoteDir.split('/').map { it.trim() }.filter { it.isNotBlank() }
        val device = config.deviceName.trim().ifBlank { "device" }.replace("/", "_")
        return remoteSegments + device
    }

    private fun buildUrl(baseUrl: String, vararg segments: String): String {
        val base = normalizeBaseUrl(baseUrl).removeSuffix("/")
        if (segments.isEmpty()) return "$base/"
        val path = segments.joinToString("/") { encodeSegment(it) }
        return "$base/$path"
    }

    private fun encodeSegment(raw: String): String =
        URLEncoder.encode(raw, "UTF-8")
            .replace("+", "%20")
            .replace("%2F", "/")

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        require(trimmed.isNotBlank()) { "WebDAV 地址不能为空" }
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }
}

