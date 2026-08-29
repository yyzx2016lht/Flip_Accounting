package com.taostudio.tapaccounting.data.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class BackupFileFormat {
    /** Directly restorable, unencrypted ZIP-based `.bak` format. */
    ZIP,

    /** Backup V2 envelope; its manifest and payload are encrypted. */
    V2_ENCRYPTED,

    /** Current whole-archive encryption protected by the user's backup PIN. */
    V3_PASSWORD,

    UNKNOWN
}

object BackupFileFormatDetector {
    private val V1_LOCAL_FILE = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
    private val V1_EMPTY_ZIP = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 5, 6)
    private val V1_SPANNED_ZIP = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 7, 8)

    fun detect(file: File): BackupFileFormat {
        if (!file.isFile) return BackupFileFormat.UNKNOWN
        val prefix = ByteArray(maxOf(BackupV2Envelope.MAGIC.size, BackupPasswordEnvelope.MAGIC.size))
        val read = FileInputStream(file).use { it.read(prefix) }
        if (read >= BackupPasswordEnvelope.MAGIC.size &&
            prefix.copyOf(BackupPasswordEnvelope.MAGIC.size).contentEquals(BackupPasswordEnvelope.MAGIC)
        ) {
            return BackupFileFormat.V3_PASSWORD
        }
        if (read >= BackupV2Envelope.MAGIC.size &&
            prefix.copyOf(BackupV2Envelope.MAGIC.size).contentEquals(BackupV2Envelope.MAGIC)
        ) {
            return BackupFileFormat.V2_ENCRYPTED
        }
        if (read >= 4) {
            val zipPrefix = prefix.copyOf(4)
            if (zipPrefix.contentEquals(V1_LOCAL_FILE) ||
                zipPrefix.contentEquals(V1_EMPTY_ZIP) ||
                zipPrefix.contentEquals(V1_SPANNED_ZIP)
            ) {
                return BackupFileFormat.ZIP
            }
        }
        return BackupFileFormat.UNKNOWN
    }

    fun isV2Encrypted(file: File): Boolean = detect(file) == BackupFileFormat.V2_ENCRYPTED
}

open class BackupV2Exception(message: String, cause: Throwable? = null) : IOException(message, cause)

class BackupFormatException(message: String, cause: Throwable? = null) : BackupV2Exception(message, cause)

class UnsupportedBackupVersionException(val foundVersion: Int) :
    BackupV2Exception("不支持的 Backup V2 envelope 版本：$foundVersion")

class BackupAuthenticationException(cause: Throwable? = null) :
    BackupV2Exception("备份密码或恢复码错误，或备份文件已被篡改", cause)

class BackupIntegrityException(message: String) : BackupV2Exception(message)

/**
 * Streaming AES-256-GCM envelope for Backup V2.
 *
 * Binary layout (all integer fields are big-endian):
 *
 * ```text
 * outer header / AAD:
 *   magic[8] = "FLIPBAK2"
 *   envelopeVersion[1] = 2
 *   flags[1] = 1 (encrypted)
 *   algorithm[1] = 1 (AES-256-GCM)
 *   nonceLength[1] = 12
 *   nonce[12]
 * encrypted plaintext:
 *   innerMagic[4] = "FBP2"
 *   manifestLength[4]
 *   manifestJson[manifestLength]
 *   payload[remaining bytes]
 * ciphertext trailer:
 *   GCM authentication tag[16]
 * ```
 *
 * The complete outer header, including the nonce, is authenticated as AAD.
 * The recovery code is used directly as the uniformly random AES-256 key and
 * is never included in the file.
 */
object BackupV2Envelope {
    internal val MAGIC = "FLIPBAK2".toByteArray(Charsets.US_ASCII)
    internal const val VERSION_OFFSET = 8

    private val INNER_MAGIC = "FBP2".toByteArray(Charsets.US_ASCII)
    private const val ENVELOPE_VERSION = 2
    private const val FLAG_ENCRYPTED = 1
    private const val ALGORITHM_AES_256_GCM = 1
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    /** Encrypts [payloadFile] to [outputFile] without loading either into memory. */
    fun encrypt(
        payloadFile: File,
        outputFile: File,
        manifest: BackupV2Manifest,
        recoveryCode: BackupRecoveryCode,
        random: SecureRandom = SecureRandom()
    ) {
        require(payloadFile.isFile) { "待加密 payload 不存在" }
        manifest.requireValid()
        outputFile.absoluteFile.parentFile?.mkdirs()
        val tempOutput = temporarySibling(outputFile, "encrypting")
        try {
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val header = buildHeader(nonce)
            val keyBytes = recoveryCode.copyKeyBytes()
            val cipher = try {
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                    updateAAD(header)
                }
            } finally {
                keyBytes.fill(0)
            }
            val manifestBytes = BackupV2ManifestCodec.encode(manifest)
            require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "Backup V2 manifest 过大" }

            val digest = MessageDigest.getInstance("SHA-256")
            var payloadSize = 0L
            BufferedOutputStream(FileOutputStream(tempOutput), BUFFER_SIZE).use { fileOutput ->
                fileOutput.write(header)
                CipherOutputStream(fileOutput, cipher).use { cipherOutput ->
                    DataOutputStream(BufferedOutputStream(cipherOutput, BUFFER_SIZE)).use { encrypted ->
                        encrypted.write(INNER_MAGIC)
                        encrypted.writeInt(manifestBytes.size)
                        encrypted.write(manifestBytes)
                        BufferedInputStream(FileInputStream(payloadFile), BUFFER_SIZE).use { payload ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = payload.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                digest.update(buffer, 0, read)
                                encrypted.write(buffer, 0, read)
                                payloadSize += read
                            }
                        }
                    }
                }
            }
            val actualHash = digest.digest().toHex()
            if (payloadSize != manifest.payloadSize || actualHash != manifest.payloadSha256) {
                throw BackupIntegrityException("payload 在 manifest 创建后发生变化")
            }
            replaceWithTemp(tempOutput, outputFile)
        } finally {
            tempOutput.delete()
        }
    }

    /**
     * Authenticates and decrypts [encryptedFile] into [outputPayloadFile].
     * The caller's output is replaced only after the GCM tag and payload hash pass.
     */
    fun decrypt(
        encryptedFile: File,
        outputPayloadFile: File,
        recoveryCode: BackupRecoveryCode
    ): BackupV2Manifest {
        require(encryptedFile.isFile) { "加密备份文件不存在" }
        outputPayloadFile.absoluteFile.parentFile?.mkdirs()
        val decryptedEnvelope = temporarySibling(outputPayloadFile, "decrypted-envelope")
        try {
            decryptEnvelopeToFile(encryptedFile, decryptedEnvelope, recoveryCode)
            val manifest = extractAndVerifyPayloadInPlace(decryptedEnvelope)
            replaceWithTemp(decryptedEnvelope, outputPayloadFile)
            return manifest
        } finally {
            decryptedEnvelope.delete()
        }
    }

    private fun decryptEnvelopeToFile(
        encryptedFile: File,
        output: File,
        recoveryCode: BackupRecoveryCode
    ) {
        try {
            BufferedInputStream(FileInputStream(encryptedFile), BUFFER_SIZE).use { rawInput ->
                val header = readHeader(rawInput)
                val keyBytes = recoveryCode.copyKeyBytes()
                val cipher = try {
                    Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(
                            Cipher.DECRYPT_MODE,
                            SecretKeySpec(keyBytes, "AES"),
                            GCMParameterSpec(GCM_TAG_BITS, header.nonce)
                        )
                        updateAAD(header.encoded)
                    }
                } finally {
                    keyBytes.fill(0)
                }
                CipherInputStream(rawInput, cipher).use { decrypted ->
                    BufferedOutputStream(FileOutputStream(output), BUFFER_SIZE).use { target ->
                        decrypted.copyTo(target, BUFFER_SIZE)
                    }
                }
            }
        } catch (error: UnsupportedBackupVersionException) {
            throw error
        } catch (error: BackupFormatException) {
            throw error
        } catch (error: Exception) {
            if (hasAuthenticationCause(error)) throw BackupAuthenticationException(error)
            throw error
        }
    }

    /**
     * Verifies the authenticated clear envelope and shifts its payload to byte zero in-place.
     * Keeping a single clear temporary file avoids needing roughly twice the payload size during restore.
     */
    private fun extractAndVerifyPayloadInPlace(clearEnvelope: File): BackupV2Manifest {
        RandomAccessFile(clearEnvelope, "rw").use { file ->
            val innerMagic = ByteArray(INNER_MAGIC.size)
            try {
                file.readFully(innerMagic)
            } catch (error: EOFException) {
                throw BackupFormatException("Backup V2 内层数据不完整", error)
            }
            if (!innerMagic.contentEquals(INNER_MAGIC)) {
                throw BackupFormatException("Backup V2 内层 magic 无效")
            }
            val manifestLength = try {
                file.readInt()
            } catch (error: EOFException) {
                throw BackupFormatException("Backup V2 缺少 manifest 长度", error)
            }
            if (manifestLength !in 1..MAX_MANIFEST_BYTES) {
                throw BackupFormatException("Backup V2 manifest 长度无效：$manifestLength")
            }
            val manifestBytes = ByteArray(manifestLength)
            try {
                file.readFully(manifestBytes)
            } catch (error: EOFException) {
                throw BackupFormatException("Backup V2 manifest 不完整", error)
            }
            val manifest = BackupV2ManifestCodec.decode(manifestBytes)
            val digest = MessageDigest.getInstance("SHA-256")
            var readPosition = file.filePointer
            var writePosition = 0L
            var payloadSize = 0L
            val clearEnvelopeSize = file.length()
            val buffer = ByteArray(BUFFER_SIZE)
            while (readPosition < clearEnvelopeSize) {
                file.seek(readPosition)
                val read = file.read(
                    buffer,
                    0,
                    minOf(buffer.size.toLong(), clearEnvelopeSize - readPosition).toInt()
                )
                if (read < 0) throw BackupFormatException("Backup V2 payload 提前结束")
                if (read == 0) continue
                digest.update(buffer, 0, read)
                file.seek(writePosition)
                file.write(buffer, 0, read)
                readPosition += read
                writePosition += read
                payloadSize += read
            }
            val payloadHash = digest.digest().toHex()
            if (payloadSize != manifest.payloadSize || payloadHash != manifest.payloadSha256) {
                throw BackupIntegrityException("Backup V2 payload 大小或 SHA-256 与 manifest 不一致")
            }
            file.setLength(payloadSize)
            return manifest
        }
    }

    private data class Header(val encoded: ByteArray, val nonce: ByteArray)

    private fun buildHeader(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES)
        return ByteArrayOutputStream(MAGIC.size + 4 + nonce.size).use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write(MAGIC)
                output.writeByte(ENVELOPE_VERSION)
                output.writeByte(FLAG_ENCRYPTED)
                output.writeByte(ALGORITHM_AES_256_GCM)
                output.writeByte(nonce.size)
                output.write(nonce)
            }
            buffer.toByteArray()
        }
    }

    private fun readHeader(input: DataInputStream): Header = readHeader(input as java.io.InputStream)

    private fun readHeader(input: java.io.InputStream): Header {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try {
            data.readFully(magic)
        } catch (error: EOFException) {
            throw BackupFormatException("文件过短，不是 Backup V2", error)
        }
        if (!magic.contentEquals(MAGIC)) throw BackupFormatException("不是 Backup V2 文件")
        val version = try {
            data.readUnsignedByte()
        } catch (error: EOFException) {
            throw BackupFormatException("Backup V2 header 不完整", error)
        }
        if (version != ENVELOPE_VERSION) throw UnsupportedBackupVersionException(version)
        val flags = data.readUnsignedByte()
        val algorithm = data.readUnsignedByte()
        val nonceLength = data.readUnsignedByte()
        if (flags != FLAG_ENCRYPTED || algorithm != ALGORITHM_AES_256_GCM || nonceLength != NONCE_BYTES) {
            throw BackupFormatException("Backup V2 加密参数无效")
        }
        val nonce = ByteArray(nonceLength)
        try {
            data.readFully(nonce)
        } catch (error: EOFException) {
            throw BackupFormatException("Backup V2 nonce 不完整", error)
        }
        return Header(buildHeader(nonce), nonce)
    }

    private fun hasAuthenticationCause(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is AEADBadTagException || current is BadPaddingException) return true
            current = current.cause
        }
        return false
    }

    private fun temporarySibling(target: File, purpose: String): File {
        val parent = target.absoluteFile.parentFile ?: throw IOException("输出文件没有父目录")
        parent.mkdirs()
        return File(parent, ".${target.name}.$purpose.${UUID.randomUUID()}.tmp")
    }

    /** Replaces the destination while retaining the old file until the staged rename succeeds. */
    private fun replaceWithTemp(staged: File, destination: File) {
        val old = if (destination.exists()) temporarySibling(destination, "previous") else null
        if (old != null && !destination.renameTo(old)) {
            throw IOException("无法暂存旧输出文件")
        }
        if (!staged.renameTo(destination)) {
            old?.renameTo(destination)
            throw IOException("无法提交 Backup V2 输出文件")
        }
        old?.delete()
    }
}
