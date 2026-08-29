package com.taostudio.tapaccounting.data.backup

import okio.BufferedSink
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.util.UUID
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
    val timestamp: String,
    /** Null means the configured device (legacy API); blank means remote root. */
    val deviceName: String? = null,
    val backupId: String? = null,
    val contentLength: Long? = null,
    val lastModified: String? = null
)

object WebDavClient {
    private val transport = WebDavTransport(
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    )

    fun testConnection(config: CloudBackupConfig) = transport.testConnection(config)

    /** Compatibility API. Prefer the File or stream-source overload for new integrations. */
    fun uploadBackup(config: CloudBackupConfig, fileName: String, bytes: ByteArray) =
        transport.uploadBackup(config, fileName, bytes.size.toLong()) {
            ByteArrayInputStream(bytes)
        }

    fun uploadBackup(config: CloudBackupConfig, fileName: String, sourceFile: File) {
        require(sourceFile.isFile) { "备份源文件不存在：${sourceFile.absolutePath}" }
        transport.uploadBackup(config, fileName, sourceFile.length()) {
            FileInputStream(sourceFile)
        }
    }

    fun uploadBackup(
        config: CloudBackupConfig,
        fileName: String,
        contentLength: Long,
        openStream: () -> InputStream
    ) = transport.uploadBackup(config, fileName, contentLength, openStream)

    fun findLatestBackup(config: CloudBackupConfig): CloudBackupEntry? =
        transport.findLatestBackup(config)

    fun findLatestBackupAcrossDevices(config: CloudBackupConfig): CloudBackupEntry? =
        transport.findLatestBackupAcrossDevices(config)

    /** Compatibility API. Prefer an OutputStream or File overload for large backups. */
    fun downloadBackup(config: CloudBackupConfig, entry: CloudBackupEntry): ByteArray {
        val output = ByteArrayOutputStream()
        transport.downloadBackup(config, entry, output)
        return output.toByteArray()
    }

    fun downloadBackup(
        config: CloudBackupConfig,
        entry: CloudBackupEntry,
        output: OutputStream
    ): Long = transport.downloadBackup(config, entry, output)

    fun downloadBackup(
        config: CloudBackupConfig,
        entry: CloudBackupEntry,
        destination: File
    ): Long = transport.downloadBackup(config, entry, destination)

    /** Current-device history; now public so callers can present restore choices. */
    fun listBackups(config: CloudBackupConfig): List<CloudBackupEntry> =
        transport.listBackups(config)

    fun listBackupDevices(config: CloudBackupConfig): List<String> =
        transport.listBackupDevices(config)

    fun listDeviceBackups(config: CloudBackupConfig, deviceName: String): List<CloudBackupEntry> =
        transport.listDeviceBackups(config, deviceName)

    /** Includes root-level legacy backups and history in every device directory. */
    fun listAllBackups(config: CloudBackupConfig): List<CloudBackupEntry> =
        transport.listAllBackups(config)

    /** Default cleanup now uses the calendar retention policy and never removes the final backup. */
    fun cleanupBackups(config: CloudBackupConfig): BackupRetentionDecision<CloudBackupEntry> =
        transport.cleanupBackupHistory(
            config,
            policy = null,
            zoneId = ZoneId.systemDefault()
        )

    /** Compatibility overload for callers that explicitly need the old count-based policy. */
    fun cleanupBackups(config: CloudBackupConfig, keepLite: Int, keepFull: Int = 3) =
        transport.cleanupBackups(config, keepLite, keepFull)

    fun cleanupBackupHistory(
        config: CloudBackupConfig,
        policy: BackupRetentionPolicy? = null,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): BackupRetentionDecision<CloudBackupEntry> =
        transport.cleanupBackupHistory(config, policy, zoneId)
}

internal class WebDavTransport(
    private val client: OkHttpClient,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private val octet: MediaType = "application/octet-stream".toMediaType()
    private val xml: MediaType = "text/xml; charset=utf-8".toMediaType()

    fun testConnection(config: CloudBackupConfig) {
        val request = Request.Builder()
            .url(normalizeBaseUrl(config.baseUrl))
            .header("Authorization", auth(config))
            .header("Depth", "0")
            .method("PROPFIND", propFindBody())
            .build()
        execute(request).use { response ->
            when (response.code) {
                207 -> Unit
                401, 403 -> throw IllegalStateException("认证失败：请检查账号或应用密码")
                else -> throw statusFailure("连接失败", response, setOf(207))
            }
        }
    }

    fun uploadBackup(
        config: CloudBackupConfig,
        fileName: String,
        contentLength: Long,
        openStream: () -> InputStream
    ) {
        require(contentLength > 0L) { "备份文件不能为空" }
        requireSafeFileName(fileName)

        val authorization = auth(config)
        val segments = deviceSegments(config, config.deviceName)
        ensureDirectory(config.baseUrl, authorization, segments)

        val partialId = BackupArtifactNames.safeSegment(idGenerator(), "partial")
        val partialName = ".partial-$partialId"
        val partialUrl = buildUrl(config.baseUrl, *(segments + partialName).toTypedArray())
        val finalUrl = buildUrl(config.baseUrl, *(segments + fileName).toTypedArray())
        var moved = false
        try {
            val put = Request.Builder()
                .url(partialUrl)
                .header("Authorization", authorization)
                .put(StreamingRequestBody(octet, contentLength, openStream))
                .build()
            execute(put).use { response ->
                requireStatus("上传临时备份失败", response, setOf(200, 201, 204))
            }
            requireRemoteLength(partialUrl, authorization, contentLength, "临时备份")

            val move = Request.Builder()
                .url(partialUrl)
                .header("Authorization", authorization)
                .header("Destination", finalUrl)
                .header("Overwrite", "F")
                .method("MOVE", null)
                .build()
            execute(move).use { response ->
                requireStatus("发布云端备份失败", response, setOf(200, 201, 204))
            }
            moved = true
            requireRemoteLength(finalUrl, authorization, contentLength, "已发布备份")
        } catch (failure: Exception) {
            val cleanupUrls = if (moved) listOf(finalUrl, partialUrl) else listOf(partialUrl)
            cleanupUrls.forEach { cleanupUrl ->
                runCatching { delete(cleanupUrl, authorization, allowMissing = true) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    fun findLatestBackup(config: CloudBackupConfig): CloudBackupEntry? =
        listBackups(config).maxWithOrNull(entryComparator)

    fun findLatestBackupAcrossDevices(config: CloudBackupConfig): CloudBackupEntry? =
        listAllBackups(config).maxWithOrNull(entryComparator)

    fun downloadBackup(
        config: CloudBackupConfig,
        entry: CloudBackupEntry,
        output: OutputStream
    ): Long {
        requireSafeFileName(entry.name)
        val authorization = auth(config)
        val segments = remoteSegments(config) + when (val device = entry.deviceName) {
            null -> listOf(safeDeviceDirectory(config.deviceName))
            "" -> emptyList()
            else -> listOf(safeDeviceDirectory(device))
        }
        val url = buildUrl(config.baseUrl, *(segments + entry.name).toTypedArray())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .get()
            .build()
        execute(request).use { response ->
            requireStatus("下载失败", response, setOf(200))
            val body = response.body ?: throw IllegalStateException("下载失败：返回内容为空")
            val headerLength = body.contentLength().takeIf { it >= 0L }
            var copied = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                }
            }
            output.flush()
            headerLength?.let {
                check(copied == it) { "下载失败：Content-Length 为 $it，实际收到 $copied" }
            }
            entry.contentLength?.let {
                check(copied == it) { "下载失败：云端目录记录长度为 $it，实际收到 $copied" }
            }
            return copied
        }
    }

    fun downloadBackup(
        config: CloudBackupConfig,
        entry: CloudBackupEntry,
        destination: File
    ): Long {
        require(!destination.exists()) { "拒绝覆盖已有文件：${destination.absolutePath}" }
        val absoluteDestination = destination.absoluteFile
        val parent = requireNotNull(absoluteDestination.parentFile) { "下载目标缺少父目录" }
        require(parent.exists() || parent.mkdirs()) {
            "无法创建下载目录：${parent.absolutePath}"
        }
        val partial = File(parent, ".partial-download-${idGenerator()}")
        require(!partial.exists()) { "下载临时文件已存在：${partial.absolutePath}" }
        var complete = false
        try {
            val count = FileOutputStream(partial).use { output ->
                val downloaded = downloadBackup(config, entry, output)
                output.fd.sync()
                downloaded
            }
            check(!absoluteDestination.exists()) { "拒绝覆盖已有文件：${absoluteDestination.absolutePath}" }
            moveDownloadedFile(partial, absoluteDestination)
            complete = true
            return count
        } finally {
            if (!complete) partial.delete()
        }
    }

    fun listBackups(config: CloudBackupConfig): List<CloudBackupEntry> {
        val authorization = auth(config)
        val segments = deviceSegments(config, config.deviceName)
        ensureDirectory(config.baseUrl, authorization, segments)
        return listBackupsAt(config, authorization, segments, config.deviceName)
    }

    fun listBackupDevices(config: CloudBackupConfig): List<String> {
        val authorization = auth(config)
        val segments = remoteSegments(config)
        ensureDirectory(config.baseUrl, authorization, segments)
        val url = buildUrl(config.baseUrl, *segments.toTypedArray())
        return propFind(url, authorization, depth = 1)
            .asSequence()
            .filter(WebDavResource::isCollection)
            .filterNot { sameResource(it.decodedPath, url) }
            .map(WebDavResource::name)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .toList()
    }

    fun listDeviceBackups(config: CloudBackupConfig, deviceName: String): List<CloudBackupEntry> {
        val authorization = auth(config)
        val segments = deviceSegments(config, deviceName)
        return listBackupsAt(config, authorization, segments, deviceName)
    }

    fun listAllBackups(config: CloudBackupConfig): List<CloudBackupEntry> {
        val authorization = auth(config)
        val rootSegments = remoteSegments(config)
        ensureDirectory(config.baseUrl, authorization, rootSegments)
        val rootUrl = buildUrl(config.baseUrl, *rootSegments.toTypedArray())
        val rootResources = propFind(rootUrl, authorization, depth = 1)
        val rootEntries = resourcesToEntries(
            rootResources.filterNot(WebDavResource::isCollection),
            deviceName = ""
        )
        val devices = rootResources.asSequence()
            .filter(WebDavResource::isCollection)
            .filterNot { sameResource(it.decodedPath, rootUrl) }
            .map(WebDavResource::name)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        return (rootEntries + devices.flatMap { device ->
            listBackupsAt(
                config = config,
                authorization = authorization,
                segments = rootSegments + safeDeviceDirectory(device),
                deviceName = device
            )
        }).sortedWith(entryComparator.reversed())
    }

    fun cleanupBackups(config: CloudBackupConfig, keepLite: Int, keepFull: Int) {
        require(keepLite >= 0 && keepFull >= 0) { "保留数量不能为负数" }
        val entries = listBackups(config)
        if (entries.size <= 1) return
        val lite = entries.filter { it.mode == "lite" }.sortedWith(entryComparator.reversed())
        val fullLike = entries.filter { it.mode != "lite" }.sortedWith(entryComparator.reversed())
        val proposed = (lite.drop(keepLite) + fullLike.drop(keepFull)).toMutableSet()
        // The compatibility cleanup API must not be able to remove the final backup.
        proposed.remove(entries.maxWithOrNull(entryComparator))
        deleteEntries(config, proposed)
    }

    fun cleanupBackupHistory(
        config: CloudBackupConfig,
        policy: BackupRetentionPolicy?,
        zoneId: ZoneId
    ): BackupRetentionDecision<CloudBackupEntry> {
        val entries = listBackups(config)
        val validEntries = entries.filter { BackupArtifactNames.parse(it.name) != null }
        val decisions = validEntries
            .groupBy { entry -> "${entry.deviceName}\u0000${entry.mode}" }
            .values
            .map { modeEntries ->
                (policy ?: BackupRetentionPolicy.forMode(modeEntries.first().mode)).decide(
                    items = modeEntries,
                    createdAt = { entry ->
                        BackupArtifactNames.parse(entry.name)!!
                            .createdAt
                            .atZone(zoneId)
                            .toInstant()
                    },
                    stableId = { entry -> entry.name }
                )
            }
        val decision = BackupRetentionDecision(
            keep = decisions.flatMap { it.keep }.sortedWith(entryComparator.reversed()),
            delete = decisions.flatMap { it.delete }.sortedWith(entryComparator.reversed())
        )
        deleteEntries(config, decision.delete)
        return decision
    }

    private fun deleteEntries(config: CloudBackupConfig, entries: Collection<CloudBackupEntry>) {
        val authorization = auth(config)
        entries.forEach { entry ->
            val device = entry.deviceName ?: config.deviceName
            val segments = remoteSegments(config) +
                if (device.isBlank()) emptyList() else listOf(safeDeviceDirectory(device))
            val url = buildUrl(config.baseUrl, *(segments + entry.name).toTypedArray())
            delete(url, authorization, allowMissing = true)
        }
    }

    private fun listBackupsAt(
        config: CloudBackupConfig,
        authorization: String,
        segments: List<String>,
        deviceName: String
    ): List<CloudBackupEntry> {
        val url = buildUrl(config.baseUrl, *segments.toTypedArray())
        return resourcesToEntries(
            propFind(url, authorization, depth = 1).filterNot(WebDavResource::isCollection),
            deviceName
        ).sortedWith(entryComparator.reversed())
    }

    private fun resourcesToEntries(
        resources: Collection<WebDavResource>,
        deviceName: String
    ): List<CloudBackupEntry> = resources.mapNotNull { resource ->
        val parsed = BackupArtifactNames.parse(resource.name) ?: return@mapNotNull null
        CloudBackupEntry(
            name = resource.name,
            mode = parsed.mode,
            timestamp = parsed.timestamp,
            deviceName = deviceName,
            backupId = parsed.backupId,
            contentLength = resource.contentLength,
            lastModified = resource.lastModified
        )
    }

    private fun requireRemoteLength(
        url: String,
        authorization: String,
        expectedLength: Long,
        label: String
    ) {
        val resource = propFind(url, authorization, depth = 0)
            .firstOrNull { sameResource(it.decodedPath, url) }
            ?: throw IllegalStateException("校验失败：找不到$label")
        val actualLength = resource.contentLength
            ?: throw IllegalStateException("校验失败：${label}未返回 Content-Length")
        check(actualLength == expectedLength) {
            "校验失败：${label}长度为 $actualLength，预期 $expectedLength"
        }
    }

    private fun propFind(url: String, authorization: String, depth: Int): List<WebDavResource> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .header("Depth", depth.toString())
            .method("PROPFIND", propFindBody())
            .build()
        execute(request).use { response ->
            requireStatus("读取云端目录失败", response, setOf(207))
            val body = response.body?.string()
                ?: throw IllegalStateException("读取云端目录失败：返回内容为空")
            return WebDavXml.parseResources(body)
        }
    }

    private fun propFindBody(): RequestBody =
        """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop>
              <d:resourcetype/><d:getcontentlength/><d:getlastmodified/>
            </d:prop></d:propfind>""".trimIndent().toRequestBody(xml)

    private fun delete(url: String, authorization: String, allowMissing: Boolean) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .delete()
            .build()
        execute(request).use { response ->
            val allowed = if (allowMissing) setOf(200, 202, 204, 404) else setOf(200, 202, 204)
            requireStatus("删除云端备份失败", response, allowed)
        }
    }

    private fun ensureDirectory(baseUrl: String, authorization: String, segments: List<String>) {
        var created = emptyList<String>()
        segments.forEach { segment ->
            created = created + segment
            val request = Request.Builder()
                .url(buildUrl(baseUrl, *created.toTypedArray()))
                .header("Authorization", authorization)
                .method("MKCOL", ByteArray(0).toRequestBody(null))
                .build()
            execute(request).use { response ->
                // 405 means the collection already exists. Other redirects or
                // generic 2xx responses are not silently treated as success.
                requireStatus("创建云端目录失败", response, setOf(200, 201, 204, 405))
            }
        }
    }

    private fun execute(request: Request): Response = client.newCall(request).execute()

    private fun requireStatus(action: String, response: Response, allowed: Set<Int>) {
        if (response.code !in allowed) throw statusFailure(action, response, allowed)
    }

    private fun statusFailure(action: String, response: Response, allowed: Set<Int>): Exception {
        val detail = runCatching { response.peekBody(512).string().trim() }
            .getOrNull()
            .orEmpty()
            .takeIf(String::isNotBlank)
            ?.let { "；${it.replace(Regex("\\s+"), " ")}" }
            .orEmpty()
        return IllegalStateException(
            "$action：HTTP ${response.code}（期望 ${allowed.sorted().joinToString("/")}）$detail"
        )
    }

    private fun auth(config: CloudBackupConfig): String =
        Credentials.basic(config.username, config.password, Charsets.UTF_8)

    private fun remoteSegments(config: CloudBackupConfig): List<String> =
        config.remoteDir.split('/')
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { requireSafeDirectory(it) }

    private fun deviceSegments(config: CloudBackupConfig, deviceName: String): List<String> =
        remoteSegments(config) + safeDeviceDirectory(deviceName)

    private fun safeDeviceDirectory(value: String): String =
        requireSafeDirectory(value.trim().ifBlank { "device" }.replace('/', '_').replace('\\', '_'))

    private fun requireSafeDirectory(value: String): String {
        require(value.isNotBlank() && value != "." && value != "..") { "无效的云端目录名" }
        require('/' !in value && '\\' !in value && '\u0000' !in value) { "无效的云端目录名" }
        return value
    }

    private fun requireSafeFileName(fileName: String) {
        require(fileName.isNotBlank() && fileName != "." && fileName != "..") { "无效的备份文件名" }
        require('/' !in fileName && '\\' !in fileName && '\u0000' !in fileName) { "无效的备份文件名" }
        require(fileName.endsWith(".bak", ignoreCase = true)) { "备份文件必须使用 .bak 扩展名" }
    }

    private fun buildUrl(baseUrl: String, vararg segments: String): String {
        val base = normalizeBaseUrl(baseUrl).removeSuffix("/")
        if (segments.isEmpty()) return "$base/"
        val path = segments.joinToString("/") { encodeSegment(it) }
        return "$base/$path"
    }

    private fun encodeSegment(raw: String): String =
        URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        require(trimmed.isNotBlank()) { "WebDAV 地址不能为空" }
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val parsed = runCatching { URI(withScheme) }.getOrNull()
        require(parsed?.scheme.equals("https", ignoreCase = true) && !parsed?.host.isNullOrBlank()) {
            "备份 WebDAV 必须使用有效的 HTTPS 地址"
        }
        require(parsed?.userInfo.isNullOrBlank()) { "请勿把账号密码写入 WebDAV 地址" }
        return if (withScheme.endsWith('/')) withScheme else "$withScheme/"
    }

    private fun normalizedPath(urlOrPath: String): String {
        val decoded = if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
            WebDavXml.decodePath(URI(urlOrPath).rawPath)
        } else {
            // Resource paths have already been percent-decoded by WebDavXml.
            urlOrPath
        }
        return "/${decoded.trim('/')}"
    }

    private fun sameResource(decodedResourcePath: String, requestedUrl: String): Boolean {
        val resourcePath = normalizedPath(decodedResourcePath)
        val requestedPath = normalizedPath(requestedUrl)
        return resourcePath == requestedPath || requestedPath.endsWith(resourcePath)
    }

    private fun moveDownloadedFile(partial: File, destination: File) {
        try {
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (_: Exception) {
            // Fall back to the same-directory native rename on older Android.
        } catch (_: LinkageError) {
            // Fall back to the same-directory native rename on older Android.
        }
        check(!destination.exists() && partial.renameTo(destination)) {
            "无法原子发布下载文件：${destination.absolutePath}"
        }
    }

    private val entryComparator =
        compareBy<CloudBackupEntry> { it.timestamp }.thenBy { it.name }
}

private class StreamingRequestBody(
    private val mediaType: MediaType,
    private val length: Long,
    private val openStream: () -> InputStream
) : RequestBody() {
    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = length

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        var written = 0L
        openStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sink.write(buffer, 0, count)
                written += count
            }
        }
        check(written == length) {
            "上传源长度为 $written，声明长度为 $length"
        }
    }
}
