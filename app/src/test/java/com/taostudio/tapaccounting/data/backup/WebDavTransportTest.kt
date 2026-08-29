package com.taostudio.tapaccounting.data.backup

import okio.Buffer
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset

class WebDavTransportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val config = CloudBackupConfig(
        baseUrl = "https://dav.example.test/dav/",
        username = "user",
        password = "password",
        remoteDir = "backups",
        deviceName = "Pixel 8"
    )

    @Test
    fun rejectsCleartextWebDavBeforeSendingCredentials() {
        val insecure = config.copy(baseUrl = "http://dav.example.test/dav/")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            transport(Interceptor { error("request must not be sent") }).testConnection(insecure)
        }
    }

    @Test
    fun `streams to partial verifies length then moves to final name`() {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        val requests = mutableListOf<Request>()
        var uploaded: ByteArray? = null
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            requests += request
            when (request.method) {
                "MKCOL" -> response(request, 405)
                "PUT" -> {
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    uploaded = buffer.readByteArray()
                    response(request, 201)
                }
                "PROPFIND" -> response(
                    request,
                    207,
                    resourceXml(request.url.encodedPath, payload.size.toLong())
                )
                "MOVE" -> response(request, 201)
                else -> error("Unexpected ${request.method}")
            }
        }
        val transport = transport(interceptor)

        transport.uploadBackup(
            config,
            "backup_Pixel_8_full_20260829_123456.bak",
            payload.size.toLong()
        ) { ByteArrayInputStream(payload) }

        assertArrayEquals(payload, uploaded)
        assertEquals(
            listOf("MKCOL", "MKCOL", "PUT", "PROPFIND", "MOVE", "PROPFIND"),
            requests.map { it.method }
        )
        val put = requests.first { it.method == "PUT" }
        assertTrue(put.url.encodedPath.endsWith("/.partial-fixed-id"))
        val move = requests.first { it.method == "MOVE" }
        assertTrue(move.url.encodedPath.endsWith("/.partial-fixed-id"))
        assertTrue(
            move.header("Destination")!!.endsWith(
                "/backup_Pixel_8_full_20260829_123456.bak"
            )
        )
        assertEquals("F", move.header("Overwrite"))
    }

    @Test
    fun `length mismatch rejects publish and deletes partial`() {
        val payload = "backup".toByteArray()
        val methods = mutableListOf<String>()
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            methods += request.method
            when (request.method) {
                "MKCOL" -> response(request, 405)
                "PUT" -> {
                    request.body!!.writeTo(Buffer())
                    response(request, 201)
                }
                "PROPFIND" -> response(
                    request,
                    207,
                    resourceXml(request.url.encodedPath, payload.size.toLong() - 1L)
                )
                "DELETE" -> response(request, 204)
                else -> error("Unexpected ${request.method}")
            }
        }

        val failure = runCatching {
            transport(interceptor).uploadBackup(
                config,
                "backup_Pixel_8_lite_20260829_123456.bak",
                payload.size.toLong()
            ) { ByteArrayInputStream(payload) }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("长度"))
        assertEquals(listOf("MKCOL", "MKCOL", "PUT", "PROPFIND", "DELETE"), methods)
        assertFalse(methods.contains("MOVE"))
    }

    @Test
    fun `unexpected put status is rejected and partial is cleaned`() {
        val methods = mutableListOf<String>()
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            methods += request.method
            when (request.method) {
                "MKCOL" -> response(request, 405)
                "PUT" -> {
                    request.body!!.writeTo(Buffer())
                    response(request, 302)
                }
                "DELETE" -> response(request, 204)
                else -> error("Unexpected ${request.method}")
            }
        }

        val failure = runCatching {
            transport(interceptor).uploadBackup(
                config,
                "backup_Pixel_8_lite_20260829_123456.bak",
                1L
            ) { ByteArrayInputStream(byteArrayOf(1)) }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("HTTP 302"))
        assertEquals(listOf("MKCOL", "MKCOL", "PUT", "DELETE"), methods)
    }

    @Test
    fun `failed post-move verification removes unverified final and partial`() {
        val payload = "backup".toByteArray()
        val requests = mutableListOf<Request>()
        var propFindCount = 0
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            requests += request
            when (request.method) {
                "MKCOL" -> response(request, 405)
                "PUT" -> {
                    request.body!!.writeTo(Buffer())
                    response(request, 201)
                }
                "PROPFIND" -> {
                    propFindCount++
                    val length = if (propFindCount == 1) payload.size.toLong() else 1L
                    response(request, 207, resourceXml(request.url.encodedPath, length))
                }
                "MOVE" -> response(request, 201)
                "DELETE" -> response(request, 204)
                else -> error("Unexpected ${request.method}")
            }
        }

        val failure = runCatching {
            transport(interceptor).uploadBackup(
                config,
                "backup_Pixel_8_lite_20260829_123456.bak",
                payload.size.toLong()
            ) { ByteArrayInputStream(payload) }
        }.exceptionOrNull()

        assertNotNull(failure)
        val deletedPaths = requests.filter { it.method == "DELETE" }.map { it.url.encodedPath }
        assertEquals(2, deletedPaths.size)
        assertTrue(deletedPaths.any { it.endsWith("/backup_Pixel_8_lite_20260829_123456.bak") })
        assertTrue(deletedPaths.any { it.endsWith("/.partial-fixed-id") })
    }

    @Test
    fun `download writes to caller stream and verifies listed length`() {
        val payload = ByteArray(180 * 1024) { (it % 193).toByte() }
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            check(request.method == "GET")
            response(request, 200, payload)
        }
        val output = ByteArrayOutputStream()

        val count = transport(interceptor).downloadBackup(
            config,
            CloudBackupEntry(
                name = "backup_Pixel_8_full_20260829_123456.bak",
                mode = "full",
                timestamp = "20260829_123456",
                contentLength = payload.size.toLong()
            ),
            output
        )

        assertEquals(payload.size.toLong(), count)
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun `file download publishes only after complete verified stream`() {
        val payload = ByteArray(96 * 1024) { (it % 173).toByte() }
        val interceptor = Interceptor { chain -> response(chain.request(), 200, payload) }
        val destination = temporaryFolder.root.resolve("restored.bak")
        val entry = CloudBackupEntry(
            name = "backup_Pixel_8_full_20260829_123456.bak",
            mode = "full",
            timestamp = "20260829_123456",
            contentLength = payload.size.toLong()
        )

        transport(interceptor).downloadBackup(config, entry, destination)

        assertArrayEquals(payload, destination.readBytes())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { it.name.startsWith(".partial-") })
    }

    @Test
    fun `failed file download never exposes destination`() {
        val payload = "short".toByteArray()
        val interceptor = Interceptor { chain -> response(chain.request(), 200, payload) }
        val destination = temporaryFolder.root.resolve("restored.bak")
        val entry = CloudBackupEntry(
            name = "backup_Pixel_8_full_20260829_123456.bak",
            mode = "full",
            timestamp = "20260829_123456",
            contentLength = payload.size.toLong() + 1L
        )

        val failure = runCatching {
            transport(interceptor).downloadBackup(config, entry, destination)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertFalse(destination.exists())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { it.name.startsWith(".partial-") })
    }

    @Test
    fun `lists root legacy and every device history`() {
        val rootBackup = "backup_old_lite_20260801_010203.bak"
        val phoneBackup = "backup_phone_full_20260829_123456_id-1.bak"
        val tabletBackup = "backup_tablet_lite_20260828_123456.bak"
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            when (request.method) {
                "MKCOL" -> response(request, 405)
                "PROPFIND" -> {
                    val path = request.url.encodedPath.trimEnd('/')
                    val body = when {
                        path.endsWith("/backups/phone") -> listingXml(
                            collection("$path/"),
                            file("$path/$phoneBackup", 22)
                        )
                        path.endsWith("/backups/tablet") -> listingXml(
                            collection("$path/"),
                            file("$path/$tabletBackup", 33)
                        )
                        path.endsWith("/backups") -> listingXml(
                            collection("$path/"),
                            collection("$path/phone/"),
                            collection("$path/tablet/"),
                            file("$path/$rootBackup", 11)
                        )
                        else -> error("Unexpected path $path")
                    }
                    response(request, 207, body)
                }
                else -> error("Unexpected ${request.method}")
            }
        }

        val entries = transport(interceptor).listAllBackups(config)

        assertEquals(setOf(rootBackup, phoneBackup, tabletBackup), entries.map { it.name }.toSet())
        assertEquals("", entries.first { it.name == rootBackup }.deviceName)
        assertEquals("phone", entries.first { it.name == phoneBackup }.deviceName)
        assertEquals("id-1", entries.first { it.name == phoneBackup }.backupId)
        assertEquals(33L, entries.first { it.name == tabletBackup }.contentLength)
    }

    @Test
    fun `calendar cleanup retains final backup of every backup mode`() {
        val deleted = mutableListOf<String>()
        val liteOld = "backup_Pixel_8_lite_20260701_120000.bak"
        val liteNew = "backup_Pixel_8_lite_20260801_120000.bak"
        val fullOld = "backup_Pixel_8_full_20260702_120000.bak"
        val fullNew = "backup_Pixel_8_full_20260802_120000.bak"
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            when (request.method) {
                "MKCOL" -> response(request, 405)
                "PROPFIND" -> {
                    val path = request.url.encodedPath.trimEnd('/')
                    response(
                        request,
                        207,
                        listingXml(
                            collection("$path/"),
                            file("$path/$liteOld", 10),
                            file("$path/$liteNew", 10),
                            file("$path/$fullOld", 10),
                            file("$path/$fullNew", 10)
                        )
                    )
                }
                "DELETE" -> {
                    deleted += request.url.encodedPath.substringAfterLast('/')
                    response(request, 204)
                }
                else -> error("Unexpected ${request.method}")
            }
        }

        val decision = transport(interceptor).cleanupBackupHistory(
            config,
            BackupRetentionPolicy(daily = 0, weekly = 0, monthly = 0, zoneId = ZoneOffset.UTC),
            ZoneOffset.UTC
        )

        assertEquals(setOf(liteNew, fullNew), decision.keep.map { it.name }.toSet())
        assertEquals(setOf(liteOld, fullOld), decision.delete.map { it.name }.toSet())
        assertEquals(setOf(liteOld, fullOld), deleted.toSet())
    }

    private fun transport(interceptor: Interceptor): WebDavTransport = WebDavTransport(
        client = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        idGenerator = { "fixed-id" }
    )

    private fun response(request: Request, code: Int, body: String = ""): Response =
        response(request, code, body.toByteArray())

    private fun response(request: Request, code: Int, body: ByteArray): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/octet-stream".toMediaType()))
            .build()

    private fun resourceXml(path: String, length: Long): String = listingXml(file(path, length))

    private fun listingXml(vararg resources: String): String =
        """<d:multistatus xmlns:d="DAV:">${resources.joinToString("")}</d:multistatus>"""

    private fun collection(path: String): String =
        """<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>"""

    private fun file(path: String, length: Long): String =
        """<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>$length</d:getcontentlength></d:prop></d:propstat></d:response>"""
}
