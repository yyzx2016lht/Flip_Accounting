package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalBackupPublisherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `publishes immutable file and removes partial`() {
        val source = temporaryFolder.newFile("source.bak")
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        source.outputStream().use { it.write(bytes) }
        val target = temporaryFolder.newFolder("published")

        val result = LocalBackupPublisher.publish(
            sourceFile = source,
            targetDirectory = target,
            deviceName = "Pixel 8",
            mode = "full",
            createdAt = Instant.parse("2026-08-29T12:34:56Z"),
            backupId = "backup-id-1",
            zoneId = ZoneOffset.UTC
        )

        assertEquals("backup_Pixel_8_full_20260829_123456_backup-id-1.bak", result.file.name)
        assertArrayEquals(bytes, result.file.readBytes())
        assertEquals(bytes.size.toLong(), result.byteCount)
        assertTrue(source.exists())
        assertTrue(target.listFiles().orEmpty().none { it.name.contains(".partial-") })
    }

    @Test
    fun `validation failure leaves existing backup and no partial`() {
        val source = temporaryFolder.newFile("source.bak").apply { writeText("new") }
        val target = temporaryFolder.newFolder("published")
        val existing = target.resolve("existing.bak").apply { writeText("valid") }

        val failure = runCatching {
            LocalBackupPublisher.publish(
                sourceFile = source,
                targetDirectory = target,
                deviceName = "device",
                mode = "lite",
                validate = { error("invalid archive") }
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("valid", existing.readText())
        assertTrue(target.listFiles().orEmpty().none { it.name.contains(".partial-") })
    }

    @Test
    fun `refuses to overwrite immutable artifact`() {
        val source = temporaryFolder.newFile("source.bak").apply { writeText("new") }
        val target = temporaryFolder.newFolder("published")
        val createdAt = Instant.parse("2026-08-29T12:34:56Z")
        val name = BackupArtifactNames.create(
            "device",
            "lite",
            createdAt,
            "same-id",
            ZoneOffset.UTC
        )
        val existing = target.resolve(name).apply { writeText("old") }

        val failure = runCatching {
            LocalBackupPublisher.publish(
                source,
                target,
                "device",
                "lite",
                createdAt,
                "same-id",
                ZoneOffset.UTC
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("old", existing.readText())
        assertFalse(target.listFiles().orEmpty().any { it.name.contains(".partial-") })
    }

    @Test
    fun `simultaneous publication of same artifact never overwrites winner`() {
        val first = temporaryFolder.newFile("first.bak").apply { writeText("first") }
        val second = temporaryFolder.newFile("second.bak").apply { writeText("second") }
        val target = temporaryFolder.newFolder("published")
        val createdAt = Instant.parse("2026-08-29T12:34:56Z")
        val ready = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        val validator: (java.io.File) -> Unit = {
            ready.countDown()
            check(ready.await(5, TimeUnit.SECONDS))
        }

        val outcomes = try {
            executor.invokeAll(
                listOf(
                    Callable {
                        "first" to runCatching {
                            LocalBackupPublisher.publish(
                                first,
                                target,
                                "device",
                                "full",
                                createdAt,
                                "same-id",
                                ZoneOffset.UTC,
                                validator
                            )
                        }
                    },
                    Callable {
                        "second" to runCatching {
                            LocalBackupPublisher.publish(
                                second,
                                target,
                                "device",
                                "full",
                                createdAt,
                                "same-id",
                                ZoneOffset.UTC,
                                validator
                            )
                        }
                    }
                )
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, outcomes.count { it.second.isSuccess })
        val winner = outcomes.single { it.second.isSuccess }.first
        val published = outcomes.single { it.second.isSuccess }.second.getOrThrow().file
        assertEquals(winner, published.readText())
        assertTrue(target.listFiles().orEmpty().none { it.name.startsWith(".partial-") })
    }
}
