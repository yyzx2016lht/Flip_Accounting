package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun backupAndRestore_preservesJsonAndMediaEntries() {
        val bannerDir = temp.newFolder("banners")
        File(bannerDir, "hero.jpg").writeText("banner")
        val voiceFile = temp.newFile("voice.m4a").apply { writeText("voice") }
        val backup = temp.newFile("backup.bak")

        BackupManager.backup(
            outputFile = backup,
            dataMap = mapOf("settings_books" to """{"selected_book_v1":"默认账本"}"""),
            bannerDir = bannerDir,
            chatMediaFiles = mapOf("chat_voice/voice.m4a" to voiceFile)
        )

        BackupManager.validateArchive(backup)
        val data = BackupManager.restore(backup)
        val modules = BackupManager.inspectArchiveModules(backup, data).associateBy(BackupV2Module::name)
        val restoredBanners = BackupManager.restoreBanners(backup, temp.newFolder("restored_banners"))
        val restoredMedia = BackupManager.restoreChatMedia(backup, temp.newFolder("restored_media"))

        assertEquals("""{"selected_book_v1":"默认账本"}""", data["settings_books"])
        assertEquals(setOf("settings_books", "banners", "chat_media"), modules.keys)
        assertEquals(1L, modules.getValue("settings_books").itemCount)
        assertTrue(BackupManager.hasBanners(backup))
        assertTrue(BackupManager.hasChatMedia(backup))
        assertEquals(listOf("hero.jpg"), restoredBanners)
        assertEquals(listOf("chat_voice/voice.m4a"), restoredMedia)
    }

    @Test
    fun restoreMedia_rejectsZipEntriesOutsideTargetDirBeforeWriting() {
        val backup = temp.newFile("evil.bak")
        ZipOutputStream(backup.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("banners/../outside_banner.txt"))
            zos.write("bad".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("chat_media/chat_voice/ok.m4a"))
            zos.write("ok".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("chat_media/../outside_voice.m4a"))
            zos.write("bad".toByteArray())
            zos.closeEntry()
        }

        val root = temp.newFolder("target")
        val restoredBanners = BackupManager.restoreBanners(backup, File(root, "banners"))
        assertThrows(BackupFormatException::class.java) {
            BackupManager.restoreChatMedia(backup, root)
        }

        assertEquals(emptyList<String>(), restoredBanners)
        assertFalse(File(root, "chat_voice/ok.m4a").exists())
        assertFalse(File(root, "outside_voice.m4a").exists())
        assertFalse(File(root, "outside_banner.txt").exists())
    }

    @Test
    fun restoreChatMedia_rejectsOtherFilesDirSubsystemsBeforeWriting() {
        val backup = temp.newFile("subsystem-escape.bak")
        ZipOutputStream(backup.outputStream()).use { zos ->
            listOf(
                "chat_media/chat_voice/allowed.m4a",
                "chat_media/asr_model/model.bin",
                "chat_media/database_downgrade_backups/app.db",
                "chat_media/banners/owned.jpg"
            ).forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write("replacement".toByteArray())
                zos.closeEntry()
            }
        }
        val root = temp.newFolder("subsystem-target")
        val protectedFiles = listOf(
            File(root, "asr_model/model.bin"),
            File(root, "database_downgrade_backups/app.db"),
            File(root, "banners/owned.jpg")
        ).onEach {
            it.parentFile.mkdirs()
            it.writeText("original")
        }

        assertThrows(BackupFormatException::class.java) {
            BackupManager.restoreChatMedia(backup, root)
        }

        assertFalse(File(root, "chat_voice/allowed.m4a").exists())
        protectedFiles.forEach { assertEquals("original", it.readText()) }
    }

    @Test
    fun chatMediaRestoreAllowlist_acceptsOnlyRegistryOwnedPaths() {
        assertTrue(BackupMediaRegistry.isAllowedRestoreRelativePath("chat_voice/voice.m4a"))
        assertTrue(BackupMediaRegistry.isAllowedRestoreRelativePath("chat_images/nested/pic.jpg"))
        assertTrue(BackupMediaRegistry.isAllowedRestoreRelativePath("chat_ai_avatar.jpg"))

        assertFalse(BackupMediaRegistry.isAllowedRestoreRelativePath("asr_model/model.bin"))
        assertFalse(BackupMediaRegistry.isAllowedRestoreRelativePath("database_downgrade_backups/app.db"))
        assertFalse(BackupMediaRegistry.isAllowedRestoreRelativePath("banners/owned.jpg"))
    }

    @Test
    fun validateArchive_rejectsUnsafeAndUnknownEntries() {
        val unsafe = temp.newFile("unsafe.bak")
        ZipOutputStream(unsafe.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("chat_media/../secret.txt"))
            zos.write("bad".toByteArray())
            zos.closeEntry()
        }
        assertThrows(BackupFormatException::class.java) {
            BackupManager.validateArchive(unsafe)
        }

        val unknown = temp.newFile("unknown.bak")
        ZipOutputStream(unknown.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("unexpected.bin"))
            zos.write("bad".toByteArray())
            zos.closeEntry()
        }
        assertThrows(BackupFormatException::class.java) {
            BackupManager.validateArchive(unknown)
        }
    }
}

