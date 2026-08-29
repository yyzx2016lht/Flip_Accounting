package com.taostudio.tapaccounting.data.sync

import com.google.gson.Gson
import com.taostudio.tapaccounting.data.sync.protocol.Manifest
import com.taostudio.tapaccounting.data.sync.protocol.Operation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.UUID

data class SharedInvite(
    val ledgerId: String,
    val ledgerName: String,
    val webdavUrl: String,
    val webdavUser: String,
    val remotePath: String,
    val memberId: String,
    val memberName: String,
    val joinOrder: Int,
    val protocolVersion: Int = 1
)

object InviteCodec {
    /** Compact binary invite. Base64url only acts as a copy-safe transport encoding. */
    const val PREFIX = "FAJ2:"
    const val LEGACY_PREFIX = "FlipAccounting-Join:"
    private const val JIANGUOYUN_URL = "https://dav.jianguoyun.com/dav/"
    private const val FLAG_DEFAULT_JIANGUOYUN_URL = 1
    private const val MAX_ENCODED_LENGTH = 8_192
    private val gson = Gson()

    fun encode(invite: SharedInvite): String {
        require(isValid(invite)) { "邀请信息无效" }
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                val usesDefaultUrl = invite.webdavUrl == JIANGUOYUN_URL
                output.writeByte(if (usesDefaultUrl) FLAG_DEFAULT_JIANGUOYUN_URL else 0)
                output.writeUuid(invite.ledgerId)
                output.writeUuid(invite.memberId)
                output.writeByte(invite.joinOrder)
                output.writeString(invite.ledgerName)
                output.writeString(invite.webdavUser)
                output.writeString(invite.memberName)
                if (!usesDefaultUrl) output.writeString(invite.webdavUrl)
            }
        }.toByteArray()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(text: String): SharedInvite? {
        val value = text.trim()
        if (value.length > MAX_ENCODED_LENGTH) return null
        return when {
            value.startsWith(PREFIX) -> decodeCompact(value.removePrefix(PREFIX))
            value.startsWith(LEGACY_PREFIX) -> decodeLegacy(value.removePrefix(LEGACY_PREFIX))
            else -> null
        }
    }

    private fun decodeCompact(payload: String): SharedInvite? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(payload)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val flags = input.readUnsignedByte()
            require(flags and FLAG_DEFAULT_JIANGUOYUN_URL.inv() == 0)
            val ledgerId = input.readUuid()
            val memberId = input.readUuid()
            val joinOrder = input.readUnsignedByte()
            val ledgerName = input.readString()
            val webdavUser = input.readString()
            val memberName = input.readString()
            val webdavUrl = if (flags and FLAG_DEFAULT_JIANGUOYUN_URL != 0) JIANGUOYUN_URL else input.readString()
            require(input.available() == 0)
            SharedInvite(
                ledgerId = ledgerId,
                ledgerName = ledgerName,
                webdavUrl = webdavUrl,
                webdavUser = webdavUser,
                remotePath = "/shared-ledger/$ledgerId",
                memberId = memberId,
                memberName = memberName,
                joinOrder = joinOrder
            ).takeIf(::isValid)
        }
    }.getOrNull()

    private fun decodeLegacy(payload: String): SharedInvite? = runCatching {
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        gson.fromJson(json, SharedInvite::class.java).takeIf(::isValid)
    }.getOrNull()

    private fun isValid(invite: SharedInvite): Boolean =
        invite.protocolVersion == 1 &&
            Operation.UUID_PATTERN.matches(invite.ledgerId) &&
            Operation.UUID_PATTERN.matches(invite.memberId) &&
            invite.joinOrder in 2..Manifest.MAX_MEMBER_COUNT &&
            invite.ledgerName.isNotBlank() && invite.ledgerName.length <= 100 &&
            invite.webdavUrl.isNotBlank() && invite.webdavUrl.length <= 2048 &&
            invite.webdavUser.isNotBlank() && invite.webdavUser.length <= 320 &&
            invite.remotePath == "/shared-ledger/${invite.ledgerId}" &&
            invite.memberName.length <= 40

    private fun DataOutputStream.writeUuid(value: String) {
        UUID.fromString(value).also {
            writeLong(it.mostSignificantBits)
            writeLong(it.leastSignificantBits)
        }
    }

    private fun DataInputStream.readUuid(): String = UUID(readLong(), readLong()).toString()

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65_535)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readUnsignedShort()
        require(size <= available())
        return String(ByteArray(size).also(::readFully), Charsets.UTF_8)
    }
}
