package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        val data = BackupManager.restore(backup)
        val restoredBanners = BackupManager.restoreBanners(backup, temp.newFolder("restored_banners"))
        val restoredMedia = BackupManager.restoreChatMedia(backup, temp.newFolder("restored_media"))

        assertEquals("""{"selected_book_v1":"默认账本"}""", data["settings_books"])
        assertTrue(BackupManager.hasBanners(backup))
        assertTrue(BackupManager.hasChatMedia(backup))
        assertEquals(listOf("hero.jpg"), restoredBanners)
        assertEquals(listOf("chat_voice/voice.m4a"), restoredMedia)
    }

    @Test
    fun restoreMedia_skipsZipEntriesOutsideTargetDir() {
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
        val restoredMedia = BackupManager.restoreChatMedia(backup, root)

        assertEquals(emptyList<String>(), restoredBanners)
        assertEquals(listOf("chat_voice/ok.m4a"), restoredMedia)
        assertTrue(File(root, "chat_voice/ok.m4a").exists())
        assertFalse(File(root, "outside_voice.m4a").exists())
        assertFalse(File(root, "outside_banner.txt").exists())
    }
}

