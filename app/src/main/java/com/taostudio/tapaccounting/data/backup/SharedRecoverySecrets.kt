package com.taostudio.tapaccounting.data.backup

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.taostudio.tapaccounting.data.sync.SharedCredentials
import com.taostudio.tapaccounting.data.sync.protocol.Operation

data class SharedRecoverySecret(
    val ledgerUuid: String,
    val webDavPassword: String
)

/**
 * Portable shared-ledger credentials. This module is only safe because every V2 archive is
 * encrypted as a whole; callers must never put its JSON into a legacy plaintext ZIP.
 */
object SharedRecoverySecrets {
    private val gson = Gson()
    private const val MAX_RECORDS = 10_000
    private const val MAX_PASSWORD_LENGTH = 4_096

    fun exportForLedgerUuids(context: Context, ledgerUuids: Collection<String>): String =
        gson.toJson(
            ledgerUuids.mapNotNull { ledgerUuid ->
                SharedCredentials.load(context, ledgerUuid)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { SharedRecoverySecret(ledgerUuid, it) }
            }
        )

    fun decode(
        payload: String,
        allowedLedgerUuids: Set<String>? = null
    ): List<SharedRecoverySecret> {
        val type = object : TypeToken<List<SharedRecoverySecret>>() {}.type
        val records: List<SharedRecoverySecret> = try {
            gson.fromJson(payload, type) ?: emptyList()
        } catch (error: Exception) {
            throw BackupFormatException("共享账本凭据模块无法解析", error)
        }
        require(records.size <= MAX_RECORDS) { "共享账本凭据数量过多" }
        require(records.map(SharedRecoverySecret::ledgerUuid).toSet().size == records.size) {
            "共享账本凭据 UUID 重复"
        }
        records.forEach { record ->
            require(Operation.UUID_PATTERN.matches(record.ledgerUuid)) { "共享账本凭据 UUID 无效" }
            require(record.webDavPassword.isNotBlank() && record.webDavPassword.length <= MAX_PASSWORD_LENGTH) {
                "共享账本应用密码无效"
            }
            require(allowedLedgerUuids == null || record.ledgerUuid in allowedLedgerUuids) {
                "共享账本凭据与恢复数据不匹配"
            }
        }
        return records
    }

    fun restore(context: Context, records: List<SharedRecoverySecret>): Int {
        records.forEach { record ->
            SharedCredentials.save(context, record.ledgerUuid, record.webDavPassword)
        }
        return records.size
    }
}
