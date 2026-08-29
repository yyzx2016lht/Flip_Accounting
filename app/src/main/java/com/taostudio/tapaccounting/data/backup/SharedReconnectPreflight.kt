package com.taostudio.tapaccounting.data.backup

import android.content.Context
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.sync.SharedLedgerService
import com.taostudio.tapaccounting.data.sync.SharedWebDavClient
import com.taostudio.tapaccounting.data.sync.protocol.ManifestValidator
import java.net.URI

/** Verifies remote membership before restored shared state or an old outbox can become active. */
object SharedReconnectPreflight {
    fun validate(
        context: Context,
        database: AppDatabase,
        data: SharedRestoreData,
        secrets: List<SharedRecoverySecret>
    ): SharedRestoreData {
        val secretByLedger = secrets.associateBy(SharedRecoverySecret::ledgerUuid)
        require(secretByLedger.keys == data.ledgers.mapTo(hashSetOf(), SharedLedgerBackup::uuid)) {
            "重新连接需要每个共享账本的坚果云应用密码"
        }
        val webDav = SharedWebDavClient()
        val service = SharedLedgerService(context, database)
        val currentMembers = data.ledgers.flatMap { ledger ->
            val uri = runCatching { URI(ledger.webdavUrl.trim()) }.getOrNull()
            require(uri?.scheme.equals("https", ignoreCase = true)) {
                "共享账本 WebDAV 必须使用 HTTPS"
            }
            val config = SharedWebDavClient.Config(
                baseUrl = ledger.webdavUrl,
                username = ledger.webdavUser,
                password = secretByLedger.getValue(ledger.uuid).webDavPassword
            )
            check(!webDav.exists(config, "${ledger.remotePath}/closed.json")) {
                "共享账本 ${ledger.name} 已在云端解散"
            }
            val manifest = service.decodeManifest(
                webDav.get(config, "${ledger.remotePath}/meta.json")
            )
            require(ManifestValidator.validate(manifest).isValid && manifest.sharedBookId == ledger.uuid) {
                "共享账本 ${ledger.name} 的云端身份不匹配"
            }
            require(manifest.members.any { it.memberId == ledger.localMemberId }) {
                "你已不在共享账本 ${ledger.name} 的远端成员列表中"
            }
            manifest.members.map { member ->
                SharedMemberBackup(
                    ledgerUuid = ledger.uuid,
                    memberId = member.memberId,
                    displayName = member.displayName,
                    joinOrder = member.joinOrder,
                    isLocal = member.memberId == ledger.localMemberId
                )
            }
        }
        return data.copy(members = currentMembers)
    }
}
