package com.taostudio.tapaccounting.data.backup

import com.google.gson.GsonBuilder
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Integrity metadata for one logical module in a Backup V2 payload. */
data class BackupV2Module(
    val name: String,
    val itemCount: Long,
    val byteSize: Long,
    val sha256: String
) {
    fun requireValid() {
        require(MODULE_NAME.matches(name)) { "无效的备份模块名：$name" }
        require(itemCount >= 0) { "模块 $name 的条目数不能为负数" }
        require(byteSize >= 0) { "模块 $name 的大小不能为负数" }
        require(SHA_256.matches(sha256)) { "模块 $name 的 SHA-256 无效" }
    }

    companion object {
        private val MODULE_NAME = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")
        private val SHA_256 = Regex("^[0-9a-f]{64}$")

        /** Builds module metadata from the exact bytes that will enter the payload. */
        fun fromFile(name: String, itemCount: Long, file: File): BackupV2Module {
            val digest = BackupV2Digest.of(file)
            return BackupV2Module(name, itemCount, digest.byteSize, digest.sha256).also {
                it.requireValid()
            }
        }
    }
}

/**
 * Authenticated manifest stored inside the encrypted Backup V2 envelope.
 * [payloadSha256] covers the raw cleartext payload bytes, excluding this manifest,
 * avoiding a self-referential hash.
 */
data class BackupV2Manifest(
    val formatVersion: Int,
    val appVersion: String,
    val dbSchemaVersion: Int,
    val backupId: String,
    val createdAt: Long,
    val moduleCount: Int,
    val modules: List<BackupV2Module>,
    val payloadSize: Long,
    val payloadSha256: String
) {
    fun requireValid() {
        require(formatVersion == FORMAT_VERSION) { "不支持的备份格式版本：$formatVersion" }
        require(appVersion.isNotBlank() && appVersion.length <= 128) { "应用版本无效" }
        require(dbSchemaVersion >= 0) { "数据库版本不能为负数" }
        require(isCanonicalUuid(backupId)) { "backupId 不是规范 UUID" }
        require(createdAt >= 0) { "创建时间不能为负数" }
        require(moduleCount == modules.size) { "模块数量与模块清单不一致" }
        require(modules.map { it.name }.toSet().size == modules.size) { "备份模块名重复" }
        modules.forEach(BackupV2Module::requireValid)
        require(payloadSize >= 0) { "payloadSize 不能为负数" }
        require(SHA_256.matches(payloadSha256)) { "payload SHA-256 无效" }
    }

    companion object {
        const val FORMAT_VERSION = 2
        private val SHA_256 = Regex("^[0-9a-f]{64}$")

        fun create(
            appVersion: String,
            dbSchemaVersion: Int,
            modules: List<BackupV2Module>,
            payloadFile: File,
            backupId: String = UUID.randomUUID().toString(),
            createdAt: Long = System.currentTimeMillis()
        ): BackupV2Manifest {
            val digest = BackupV2Digest.of(payloadFile)
            return BackupV2Manifest(
                formatVersion = FORMAT_VERSION,
                appVersion = appVersion,
                dbSchemaVersion = dbSchemaVersion,
                backupId = backupId,
                createdAt = createdAt,
                moduleCount = modules.size,
                modules = modules.toList(),
                payloadSize = digest.byteSize,
                payloadSha256 = digest.sha256
            ).also(BackupV2Manifest::requireValid)
        }

        private fun isCanonicalUuid(value: String): Boolean = runCatching {
            UUID.fromString(value).toString() == value
        }.getOrDefault(false)
    }
}

/** Fast post-backup check based on the inventory of the archive that was actually sealed. */
internal fun BackupV2Manifest.sharedRecoveryReadiness(): SharedRecoveryReadiness {
    val itemCounts = modules.associate { it.name to it.itemCount }
    val ledgerCount = itemCounts[BackupModuleId.SHARED_LEDGERS] ?: 0L
    if (ledgerCount == 0L) return SharedRecoveryReadiness.NOT_PRESENT

    val memberCount = itemCounts[BackupModuleId.SHARED_MEMBERS] ?: 0L
    val credentialCount = itemCounts[BackupModuleId.SHARED_SECRETS] ?: 0L
    return if (memberCount >= ledgerCount && credentialCount == ledgerCount) {
        SharedRecoveryReadiness.READY
    } else {
        SharedRecoveryReadiness.INCOMPLETE
    }
}

data class BackupV2FileDigest(val byteSize: Long, val sha256: String)

object BackupV2Digest {
    private const val BUFFER_SIZE = 64 * 1024

    fun of(file: File): BackupV2FileDigest = file.inputStream().buffered().use(::of)

    fun of(input: InputStream): BackupV2FileDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            total += read
        }
        return BackupV2FileDigest(total, digest.digest().toHex())
    }
}

internal object BackupV2ManifestCodec {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun encode(manifest: BackupV2Manifest): ByteArray {
        manifest.requireValid()
        return gson.toJson(manifest).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): BackupV2Manifest {
        val manifest = try {
            gson.fromJson(String(bytes, Charsets.UTF_8), BackupV2Manifest::class.java)
        } catch (error: Exception) {
            throw BackupFormatException("Backup V2 manifest 无法解析", error)
        }
        try {
            manifest.requireValid()
        } catch (error: Exception) {
            throw BackupFormatException("Backup V2 manifest 无效：${error.message}", error)
        }
        return manifest
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
