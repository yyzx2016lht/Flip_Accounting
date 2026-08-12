package com.taostudio.tapaccounting.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.Budget
import com.taostudio.tapaccounting.data.local.entity.SyncOperation
import com.taostudio.tapaccounting.data.local.entity.SyncState
import com.taostudio.tapaccounting.data.sync.protocol.Operation
import com.taostudio.tapaccounting.data.sync.protocol.ManifestValidator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class SharedSyncEngine(private val context: Context, private val db: AppDatabase) {
    private val gson = Gson()
    private val webDav = SharedWebDavClient()

    suspend fun syncAll() = db.sharedLedgerDao().getAll().forEach { runCatching { syncLedger(it.id) } }

    suspend fun syncLedger(ledgerId: Long) = lock(ledgerId).withLock {
        val ledger = db.sharedLedgerDao().getById(ledgerId) ?: return@withLock
        val current = db.syncStateDao().get(ledgerId) ?: SyncState(ledgerId, com.taostudio.tapaccounting.DeviceIdManager.getDeviceId(context))
        val cooldownUntil = context.getSharedPreferences("shared_sync", Context.MODE_PRIVATE)
            .getLong("webdav_cooldown_until", 0L)
        if (cooldownUntil > System.currentTimeMillis()) {
            val minutes = ((cooldownUntil - System.currentTimeMillis() + 59_999) / 60_000).coerceAtLeast(1)
            error("坚果云正在冷却，请 $minutes 分钟后再试")
        }
        db.syncStateDao().save(current.copy(isSyncing = true, lastError = null))
        try {
            val password = SharedCredentials.load(context, ledger.uuid) ?: error("缺少 WebDAV 应用密码")
            val config = SharedWebDavClient.Config(ledger.webdavUrl, ledger.webdavUser, password)
            if (webDav.exists(config, "${ledger.remotePath}/closed.json")) {
                SharedLedgerService(context, db).exitKeepingLocalCopy(ledgerId, syncFirst = false)
                return@withLock
            }
            val localBookName = db.bookDao().getById(ledger.bookId)?.name ?: error("本地账本不存在")
            refreshMemberNames(ledger.id, ledger.uuid, ledger.remotePath, config)
            enqueueMissingIcons(ledger.id, ledger.uuid, ledger.localMemberId, localBookName)
            webDav.ensureDirectory(config, "${ledger.remotePath}/operations")
            val queuedBatches = db.syncQueueDao().getByLedgerId(ledgerId).chunked(500)
            if (queuedBatches.isNotEmpty()) webDav.ensureDirectory(config, "${ledger.remotePath}/operations/batches")
            queuedBatches.forEach { batch ->
                try {
                    val first = SharedOperationCodec.decode(batch.first().operationJson) ?: error("本地共享操作无效")
                    val batchId = UUID.nameUUIDFromBytes(batch.joinToString("|") { it.operationId }.toByteArray()).toString()
                    val month = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date(batch.first().createdAt))
                    val path = "${ledger.remotePath}/operations/batches/$month/${first.deviceId}/$batchId.json.gz"
                    if (!webDav.exists(config, path)) {
                        webDav.ensureDirectory(config, path.substringBeforeLast('/'))
                        webDav.putGzip(config, path, SharedOperationBundleCodec.encode(batch.map { it.operationJson }))
                    }
                    batch.forEach { db.syncQueueDao().delete(it.operationId) }
                } catch (e: Exception) {
                    batch.forEach { db.syncQueueDao().markFailure(it.operationId, e.message ?: "上传失败") }
                    throw e
                }
            }
            val files = webDav.listOperations(config, "${ledger.remotePath}/operations")
            files.filter { it.substringAfterLast('/').removeSuffix(".json") != "meta" }.forEach { relative ->
                val path = "${ledger.remotePath}/operations/${relative.removePrefix("operations/")}"
                if (path.endsWith(".json.gz", true)) {
                    val bundled = runCatching {
                        SharedOperationBundleCodec.decode(webDav.getBytes(config, path))
                    }.getOrNull() ?: return@forEach
                    bundled.forEach { (op, raw) ->
                        if (!db.syncOperationDao().exists(op.operationId)) applyRemote(ledgerId, ledger.bookId, localBookName, op, raw)
                    }
                } else {
                    val raw = runCatching { webDav.get(config, path) }.getOrNull() ?: return@forEach
                    val op = SharedOperationCodec.decode(raw) ?: return@forEach
                    if (!db.syncOperationDao().exists(op.operationId)) applyRemote(ledgerId, ledger.bookId, localBookName, op, raw)
                }
            }
            db.syncStateDao().save(current.copy(lastSyncTime = System.currentTimeMillis(), isSyncing = false, lastError = null))
        } catch (e: Exception) {
            if (e.message?.contains("坚果云暂时繁忙") == true) {
                context.getSharedPreferences("shared_sync", Context.MODE_PRIVATE).edit()
                    .putLong("webdav_cooldown_until", System.currentTimeMillis() + 15 * 60_000L)
                    .apply()
            }
            db.syncStateDao().save(current.copy(isSyncing = false, lastError = e.message ?: "同步失败"))
            throw e
        }
    }

    private suspend fun refreshMemberNames(
        ledgerId: Long,
        ledgerUuid: String,
        remotePath: String,
        config: SharedWebDavClient.Config
    ) {
        val manifest = SharedLedgerService(context, db)
            .decodeManifest(webDav.get(config, "$remotePath/meta.json"))
        require(manifest.sharedBookId == ledgerUuid && ManifestValidator.validate(manifest).isValid) {
            "远端共享账本信息无效"
        }
        manifest.members.forEach { remote ->
            db.sharedMemberDao().get(ledgerId, remote.memberId)?.let { local ->
                if (local.displayName != remote.displayName) {
                    db.sharedMemberDao().updateDisplayName(ledgerId, remote.memberId, remote.displayName)
                }
            }
        }
    }

    private suspend fun applyRemote(ledgerId: Long, bookId: Long, bookName: String, op: Operation, raw: String) {
        if (db.sharedMemberDao().get(ledgerId, op.memberId) == null) return
        if (op.entityType == "bill") {
            val current = db.billDao().getBySharedId(op.entityId)
            if (current?.memberId != null && current.memberId != op.memberId) return
        }
        db.withTransaction {
            val old = db.syncOperationDao().getWinner(ledgerId, op.entityType, op.entityId)
            val tombstoned = op.type != "delete" && db.syncOperationDao().hasDelete(ledgerId, op.entityType, op.entityId)
            val wins = !tombstoned && wins(old, op)
            if (wins) when (op.entityType) {
                "bill" -> applyBill(bookName, op)
                "budget" -> applyBudget(bookId, bookName, op)
            }
            db.syncOperationDao().insertIgnore(SyncOperation(op.operationId, ledgerId, op.entityType, op.entityId, op.type, op.revision, op.deviceId, op.memberId, raw, System.currentTimeMillis()))
        }
    }

    private suspend fun enqueueMissingIcons(ledgerId: Long, ledgerUuid: String, localMemberId: String, bookName: String) {
        db.billDao().getAllByBookName(bookName)
            .filter { it.isShared && it.memberId == localMemberId && it.sharedId != null && it.cateIcon.isNullOrBlank() }
            .forEach { bill ->
                val icon = com.taostudio.tapaccounting.CategoryIconHelper
                    .findCategoryIcon(context, bill.categoryName, bill.type)
                    .takeIf { it.isNotBlank() } ?: return@forEach
                val revision = db.syncOperationDao().maxRevision(ledgerId, "bill", bill.sharedId!!) + 1
                val updated = bill.copy(cateIcon = icon, sharedRevision = revision,
                    sharedDeviceId = com.taostudio.tapaccounting.DeviceIdManager.getDeviceId(context))
                db.billDao().updateBill(updated)
                SharedLedgerService(context, db).enqueue(
                    ledgerId, ledgerUuid, "bill", bill.sharedId, "update", revision, localMemberId,
                    gson.toJsonTree(updated.copy(id = 0, accountId = null, toAccountId = null,
                        accountName = "", toAccountName = "", accountBalanceAfter = null,
                        toAccountBalanceAfter = null)).asJsonObject
                )
            }
    }

    private suspend fun applyBill(bookName: String, op: Operation) {
        val existing = db.billDao().getBySharedId(op.entityId)
        if (op.type == "delete") {
            existing?.let { db.billDao().delete(it) }
            return
        }
        val decoded = gson.fromJson(op.payload, Bill::class.java)
        val localCategoryId = decoded.categoryName.takeIf { it.isNotBlank() }
            ?.let { db.categoryDao().getCategoryByNameAndType(it.substringAfterLast(" - "), decoded.type)?.id }
        val incoming = decoded.copy(
            id = existing?.id ?: 0, bookName = bookName, sharedId = op.entityId,
            memberId = op.memberId, isShared = true, sharedRevision = op.revision, categoryId = localCategoryId,
            sharedDeviceId = op.deviceId,
            relatedBillId = decoded.relatedSharedId?.let { db.billDao().getBySharedId(it)?.id },
            accountId = null, toAccountId = null,
            accountName = "", toAccountName = "", accountBalanceAfter = null, toAccountBalanceAfter = null
        )
        val savedId = if (existing == null) db.billDao().insertBill(incoming) else {
            db.billDao().updateBill(incoming)
            existing.id
        }
        db.billDao().linkPendingSharedRefunds(op.entityId, savedId)
    }

    private suspend fun applyBudget(bookId: Long, bookName: String, op: Operation) {
        val existing = db.budgetDao().getBySharedId(op.entityId)
        if (op.type == "delete") {
            existing?.let { db.budgetDao().delete(it) }
            return
        }
        val decoded = gson.fromJson(op.payload, Budget::class.java)
        val localCategoryId = decoded.categoryName?.takeIf { it.isNotBlank() }
            ?.let { db.categoryDao().getCategoryByNameAndType(it.substringAfterLast(" - "), Bill.TYPE_EXPENSE)?.id }
        val localCategoryKey = when {
            decoded.categoryKey == Budget.TOTAL_CATEGORY_KEY -> Budget.TOTAL_CATEGORY_KEY
            localCategoryId != null -> localCategoryId
            else -> stableNegativeKey(op.entityId)
        }
        val incoming = decoded.copy(
            id = existing?.id ?: 0, bookId = bookId, bookName = bookName, sharedId = op.entityId,
            revision = op.revision, isShared = true, sharedDeviceId = op.deviceId,
            categoryId = localCategoryId, categoryKey = localCategoryKey
        )
        if (existing == null) db.budgetDao().insert(incoming) else db.budgetDao().update(incoming)
    }

    companion object {
        private val locks = mutableMapOf<Long, Mutex>()
        private fun lock(id: Long) = synchronized(locks) { locks.getOrPut(id) { Mutex() } }
        internal fun wins(old: SyncOperation?, incoming: Operation): Boolean = old == null ||
            (old.action != "delete" || incoming.type == "delete") &&
            (incoming.revision > old.revision || incoming.revision == old.revision && incoming.deviceId > old.deviceId)
        private fun stableNegativeKey(id: String): Long {
            val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            var value = 0L
            repeat(8) { value = (value shl 8) or (bytes[it].toLong() and 0xff) }
            return if (value == Long.MIN_VALUE) Long.MIN_VALUE + 1 else -kotlin.math.abs(value).coerceAtLeast(1)
        }
    }
}
