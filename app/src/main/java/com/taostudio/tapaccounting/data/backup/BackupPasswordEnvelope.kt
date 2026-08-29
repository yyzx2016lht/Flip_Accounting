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

/**
 * Password-protected, streaming backup envelope.
 *
 * The KDF salt and cost are authenticated header metadata. Only the derived key is cached on the
 * current installation; the PIN is never written to preferences or the archive.
 */
object BackupPasswordEnvelope {
    internal val MAGIC = "FLIPBAK3".toByteArray(Charsets.US_ASCII)
    internal const val VERSION_OFFSET = 8

    private val INNER_MAGIC = "FBP3".toByteArray(Charsets.US_ASCII)
    private const val ENVELOPE_VERSION = 3
    private const val FLAG_ENCRYPTED = 1
    private const val ALGORITHM_AES_256_GCM = 1
    private const val KDF_PBKDF2_SHA256 = 1
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    fun readKdfParameters(file: File): BackupPasswordKdfParameters =
        BufferedInputStream(FileInputStream(file), BUFFER_SIZE).use { input ->
            val parameters = readHeader(input).parameters
            BackupPasswordKdfParameters(parameters.salt.copyOf(), parameters.iterations)
        }

    /** Encrypts [payloadFile] without loading the archive or attachments into memory. */
    fun encrypt(
        payloadFile: File,
        outputFile: File,
        manifest: BackupV2Manifest,
        keyMaterial: BackupPasswordKeyMaterial,
        random: SecureRandom = SecureRandom()
    ) {
        require(payloadFile.isFile) { "待加密 payload 不存在" }
        manifest.requireValid()
        keyMaterial.requireValid()
        outputFile.absoluteFile.parentFile?.mkdirs()
        val tempOutput = temporarySibling(outputFile, "encrypting")
        try {
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val header = buildHeader(keyMaterial.parameters, nonce)
            val keyBytes = keyMaterial.keyBytes.copyOf()
            val cipher = try {
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                    updateAAD(header)
                }
            } finally {
                keyBytes.fill(0)
            }
            val manifestBytes = BackupV2ManifestCodec.encode(manifest)
            require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "备份 manifest 过大" }

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
            if (payloadSize != manifest.payloadSize || digest.digest().toHex() != manifest.payloadSha256) {
                throw BackupIntegrityException("payload 在 manifest 创建后发生变化")
            }
            replaceWithTemp(tempOutput, outputFile)
        } finally {
            tempOutput.delete()
        }
    }

    /** Authenticates and decrypts to [outputPayloadFile], publishing only a fully verified ZIP. */
    fun decrypt(
        encryptedFile: File,
        outputPayloadFile: File,
        keyBytes: ByteArray
    ): BackupV2Manifest {
        require(encryptedFile.isFile) { "加密备份文件不存在" }
        require(keyBytes.size == BackupPasswordCrypto.KEY_BYTES) { "备份密钥长度无效" }
        outputPayloadFile.absoluteFile.parentFile?.mkdirs()
        val decryptedEnvelope = temporarySibling(outputPayloadFile, "decrypted-envelope")
        try {
            decryptEnvelopeToFile(encryptedFile, decryptedEnvelope, keyBytes)
            val manifest = extractAndVerifyPayloadInPlace(decryptedEnvelope)
            replaceWithTemp(decryptedEnvelope, outputPayloadFile)
            return manifest
        } finally {
            decryptedEnvelope.delete()
        }
    }

    private fun decryptEnvelopeToFile(encryptedFile: File, output: File, suppliedKey: ByteArray) {
        try {
            BufferedInputStream(FileInputStream(encryptedFile), BUFFER_SIZE).use { rawInput ->
                val header = readHeader(rawInput)
                val keyBytes = suppliedKey.copyOf()
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

    private fun extractAndVerifyPayloadInPlace(clearEnvelope: File): BackupV2Manifest {
        RandomAccessFile(clearEnvelope, "rw").use { file ->
            val innerMagic = ByteArray(INNER_MAGIC.size)
            try {
                file.readFully(innerMagic)
            } catch (error: EOFException) {
                throw BackupFormatException("加密备份内层数据不完整", error)
            }
            if (!innerMagic.contentEquals(INNER_MAGIC)) throw BackupFormatException("加密备份内层 magic 无效")
            val manifestLength = try {
                file.readInt()
            } catch (error: EOFException) {
                throw BackupFormatException("加密备份缺少 manifest 长度", error)
            }
            if (manifestLength !in 1..MAX_MANIFEST_BYTES) {
                throw BackupFormatException("加密备份 manifest 长度无效：$manifestLength")
            }
            val manifestBytes = ByteArray(manifestLength)
            try {
                file.readFully(manifestBytes)
            } catch (error: EOFException) {
                throw BackupFormatException("加密备份 manifest 不完整", error)
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
                val read = file.read(buffer, 0, minOf(buffer.size.toLong(), clearEnvelopeSize - readPosition).toInt())
                if (read < 0) throw BackupFormatException("加密备份 payload 提前结束")
                if (read == 0) continue
                digest.update(buffer, 0, read)
                file.seek(writePosition)
                file.write(buffer, 0, read)
                readPosition += read
                writePosition += read
                payloadSize += read
            }
            if (payloadSize != manifest.payloadSize || digest.digest().toHex() != manifest.payloadSha256) {
                throw BackupIntegrityException("加密备份 payload 大小或 SHA-256 与 manifest 不一致")
            }
            file.setLength(payloadSize)
            return manifest
        }
    }

    private data class Header(
        val encoded: ByteArray,
        val parameters: BackupPasswordKdfParameters,
        val nonce: ByteArray
    )

    private fun buildHeader(parameters: BackupPasswordKdfParameters, nonce: ByteArray): ByteArray {
        parameters.requireValid()
        require(nonce.size == NONCE_BYTES)
        return ByteArrayOutputStream(MAGIC.size + 12 + parameters.salt.size + nonce.size).use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write(MAGIC)
                output.writeByte(ENVELOPE_VERSION)
                output.writeByte(FLAG_ENCRYPTED)
                output.writeByte(ALGORITHM_AES_256_GCM)
                output.writeByte(KDF_PBKDF2_SHA256)
                output.writeInt(parameters.iterations)
                output.writeByte(parameters.salt.size)
                output.write(parameters.salt)
                output.writeByte(nonce.size)
                output.write(nonce)
            }
            buffer.toByteArray()
        }
    }

    private fun readHeader(input: java.io.InputStream): Header {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try {
            data.readFully(magic)
        } catch (error: EOFException) {
            throw BackupFormatException("文件过短，不是密码加密备份", error)
        }
        if (!magic.contentEquals(MAGIC)) throw BackupFormatException("不是密码加密备份")
        val version = try {
            data.readUnsignedByte()
        } catch (error: EOFException) {
            throw BackupFormatException("密码加密备份 header 不完整", error)
        }
        if (version != ENVELOPE_VERSION) throw UnsupportedBackupVersionException(version)
        val flags = data.readUnsignedByte()
        val algorithm = data.readUnsignedByte()
        val kdf = data.readUnsignedByte()
        val iterations = data.readInt()
        val saltLength = data.readUnsignedByte()
        if (flags != FLAG_ENCRYPTED || algorithm != ALGORITHM_AES_256_GCM ||
            kdf != KDF_PBKDF2_SHA256 || saltLength != BackupPasswordCrypto.SALT_BYTES
        ) {
            throw BackupFormatException("密码加密备份参数无效")
        }
        val salt = ByteArray(saltLength)
        data.readFully(salt)
        val parameters = BackupPasswordKdfParameters(salt, iterations)
        try {
            parameters.requireValid()
        } catch (error: IllegalArgumentException) {
            throw BackupFormatException(error.message ?: "密码派生参数无效", error)
        }
        val nonceLength = data.readUnsignedByte()
        if (nonceLength != NONCE_BYTES) throw BackupFormatException("密码加密备份 nonce 长度无效")
        val nonce = ByteArray(nonceLength)
        data.readFully(nonce)
        return Header(buildHeader(parameters, nonce), parameters, nonce)
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

    private fun replaceWithTemp(staged: File, destination: File) {
        val old = if (destination.exists()) temporarySibling(destination, "previous") else null
        if (old != null && !destination.renameTo(old)) throw IOException("无法暂存旧输出文件")
        if (!staged.renameTo(destination)) {
            old?.renameTo(destination)
            throw IOException("无法提交密码加密备份")
        }
        old?.delete()
    }
}
