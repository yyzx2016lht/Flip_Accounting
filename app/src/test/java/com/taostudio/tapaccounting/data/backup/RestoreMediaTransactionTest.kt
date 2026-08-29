package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RestoreMediaTransactionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `commit keeps new files and removes rollback copies and staging`() {
        val fixture = coreFixture()
        val oldBanner = fixture.root.resolve("banners/banner.jpg").apply {
            parentFile.mkdirs()
            writeText("old-banner")
        }
        val stagedBanner = fixture.stage("banners/banner.jpg", "new-banner")
        val stagedImage = fixture.stage("chat_images/new.jpg", "new-image")

        fixture.core.publish(listOf(stagedBanner, stagedImage))

        assertEquals(RestoreMediaTransactionState.PUBLISHED, fixture.core.state)
        assertEquals("new-banner", oldBanner.readText())
        assertEquals("new-image", fixture.root.resolve("chat_images/new.jpg").readText())
        assertTrue(
            oldBanner.parentFile.listFiles().orEmpty()
                .any { it.name.startsWith(".restore-media-old-") }
        )

        fixture.core.commit()

        assertEquals(RestoreMediaTransactionState.COMMITTED, fixture.core.state)
        assertEquals("new-banner", oldBanner.readText())
        assertFalse(fixture.staging.exists())
        assertTrue(
            fixture.root.walkTopDown().none { it.name.startsWith(".restore-media-old-") }
        )
    }

    @Test
    fun `rollback restores replaced root file and deletes newly added tree`() {
        val fixture = coreFixture()
        val avatar = fixture.root.resolve("chat_ai_avatar.jpg").apply { writeText("old-avatar") }
        val stagedAvatar = fixture.stage("chat_ai_avatar.jpg", "new-avatar")
        val stagedVoice = fixture.stage("chat_voice/session/voice.m4a", "new-voice")

        fixture.core.publish(listOf(stagedAvatar, stagedVoice))
        fixture.core.rollback()

        assertEquals(RestoreMediaTransactionState.ROLLED_BACK, fixture.core.state)
        assertEquals("old-avatar", avatar.readText())
        assertFalse(fixture.root.resolve("chat_voice/session/voice.m4a").exists())
        assertFalse(fixture.root.resolve("chat_voice").exists())
        assertFalse(fixture.staging.exists())
        assertTrue(
            fixture.root.walkTopDown().none { it.name.startsWith(".restore-media-old-") }
        )
    }

    @Test
    fun `second publish failure automatically restores every prior target`() {
        val operations = FailSecondStagedRename()
        val fixture = coreFixture(operations)
        val oldBanner = fixture.root.resolve("banners/a.jpg").apply {
            parentFile.mkdirs()
            writeText("old-a")
        }
        val first = fixture.stage("banners/a.jpg", "new-a")
        val second = fixture.stage("chat_images/b.jpg", "new-b")

        val failure = runCatching {
            fixture.core.publish(listOf(first, second))
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(RestoreMediaTransactionState.ROLLED_BACK, fixture.core.state)
        assertEquals("old-a", oldBanner.readText())
        assertFalse(fixture.root.resolve("chat_images/b.jpg").exists())
        assertFalse(fixture.staging.exists())
        assertTrue(
            fixture.root.walkTopDown().none { it.name.startsWith(".restore-media-old-") }
        )
    }

    @Test
    fun `canonical escape is rejected before final files are touched`() {
        val fixture = coreFixture()
        val staged = fixture.stage("safe.txt", "payload")
        val escaped = fixture.root.parentFile.resolve("escape.txt")

        val failure = runCatching {
            fixture.core.publish(listOf(staged.copy(relativePath = "../escape.txt")))
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(RestoreMediaTransactionState.ROLLED_BACK, fixture.core.state)
        assertFalse(escaped.exists())
        assertFalse(fixture.staging.exists())
    }

    @Test
    fun `validated zip stages without mutation then transaction rollback is symmetric`() {
        val root = temporaryFolder.newFolder("files")
        val banner = root.resolve("banners/banner.jpg").apply {
            parentFile.mkdirs()
            writeText("old-banner")
        }
        val archive = temporaryFolder.newFile("backup.bak")
        writeZip(
            archive,
            "books.json" to "[]",
            "banners/banner.jpg" to "new-banner",
            "chat_media/chat_images/pic.jpg" to "new-picture",
            "chat_media/chat_ai_avatar.jpg" to "new-avatar"
        )

        val transaction = RestoreMediaTransaction.stageValidatedZip(archive, root)

        assertEquals(RestoreMediaTransactionState.STAGED, transaction.state)
        assertEquals("old-banner", banner.readText())
        assertFalse(root.resolve("chat_images/pic.jpg").exists())
        assertEquals(
            setOf("banners/banner.jpg", "chat_images/pic.jpg", "chat_ai_avatar.jpg"),
            transaction.stagedRelativePaths.toSet()
        )

        transaction.publish()
        assertEquals("new-banner", banner.readText())
        assertEquals("new-picture", root.resolve("chat_images/pic.jpg").readText())
        transaction.rollback()

        assertEquals("old-banner", banner.readText())
        assertFalse(root.resolve("chat_images/pic.jpg").exists())
        assertFalse(root.resolve("chat_ai_avatar.jpg").exists())
    }

    @Test
    fun `close automatically rolls back an uncommitted publication`() {
        val root = temporaryFolder.newFolder("files")
        val existing = root.resolve("banners/banner.jpg").apply {
            parentFile.mkdirs()
            writeText("old")
        }
        val archive = temporaryFolder.newFile("backup.bak")
        writeZip(archive, "banners/banner.jpg" to "new")

        RestoreMediaTransaction.stageValidatedZip(archive, root).use { transaction ->
            transaction.publish()
            assertEquals("new", existing.readText())
        }

        assertEquals("old", existing.readText())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".restore-media-staging-") })
    }

    @Test
    fun `zip traversal and other filesDir subsystems are rejected and staging is cleaned`() {
        val traversalRoot = temporaryFolder.newFolder("traversal-files")
        val traversalZip = temporaryFolder.newFile("traversal.bak")
        writeZip(traversalZip, "chat_media/../escape.txt" to "bad")

        assertNotNull(
            runCatching {
                RestoreMediaTransaction.stageValidatedZip(traversalZip, traversalRoot)
            }.exceptionOrNull()
        )
        assertFalse(traversalRoot.parentFile.resolve("escape.txt").exists())
        assertTrue(
            traversalRoot.listFiles().orEmpty()
                .none { it.name.startsWith(".restore-media-staging-") }
        )

        listOf(
            "asr_model/model.bin",
            "database_downgrade_backups/app.db",
            "banners/owned.jpg"
        ).forEachIndexed { index, forbiddenPath ->
            val forbiddenRoot = temporaryFolder.newFolder("forbidden-files-$index")
            val forbiddenZip = temporaryFolder.newFile("forbidden-$index.bak")
            writeZip(forbiddenZip, "chat_media/$forbiddenPath" to "replacement")

            assertNotNull(
                runCatching {
                    RestoreMediaTransaction.stageValidatedZip(forbiddenZip, forbiddenRoot)
                }.exceptionOrNull()
            )
            assertFalse(forbiddenRoot.resolve(forbiddenPath).exists())
            assertTrue(
                forbiddenRoot.listFiles().orEmpty()
                    .none { it.name.startsWith(".restore-media-staging-") }
            )
        }
    }

    private fun coreFixture(
        operations: MediaFileOperations = JvmMediaFileOperations
    ): CoreFixture {
        val root = temporaryFolder.newFolder("files")
        val staging = root.resolve(".restore-media-staging-test").apply { mkdir() }
        val payload = staging.resolve("payload").apply { mkdir() }
        return CoreFixture(
            root = root,
            staging = staging,
            payload = payload,
            core = MediaFilePublicationCore(root, staging, "test", operations)
        )
    }

    private fun writeZip(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }

    private data class CoreFixture(
        val root: File,
        val staging: File,
        val payload: File,
        val core: MediaFilePublicationCore
    ) {
        fun stage(relativePath: String, content: String): StagedMediaFile {
            val file = payload.resolve(relativePath.replace('/', File.separatorChar))
            file.parentFile.mkdirs()
            file.writeText(content)
            return StagedMediaFile(relativePath, file)
        }
    }

    private class FailSecondStagedRename : MediaFileOperations {
        private var stagedMoves = 0

        override fun rename(source: File, destination: File): Boolean {
            if (source.path.contains(".restore-media-staging-")) {
                stagedMoves++
                if (stagedMoves == 2) return false
            }
            return source.renameTo(destination)
        }

        override fun delete(file: File): Boolean = !file.exists() || file.delete()
    }
}
