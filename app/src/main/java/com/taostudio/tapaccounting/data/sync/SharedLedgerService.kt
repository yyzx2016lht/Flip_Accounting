package com.taostudio.tapaccounting.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.taostudio.tapaccounting.DeviceIdManager
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.*
import com.taostudio.tapaccounting.data.sync.protocol.Manifest
import com.taostudio.tapaccounting.data.sync.protocol.ManifestMember
import com.taostudio.tapaccounting.data.sync.protocol.ManifestValidator
import com.taostudio.tapaccounting.data.sync.protocol.Operation
import com.taostudio.tapaccounting.data.sync.protocol.StrictJsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SharedLedgerService(private val context: Context, private val db: AppDatabase) {
    private val gson = Gson()
    private val webDav = SharedWebDavClient()

    suspend fun create(
        bookName: String,
        ledgerName: String,
        memberNames: List<String>,
        webdavUrl: String,
        webdavUser: String,
        password: String
    ): String {
        require(memberNames.size == SharedLedger.ACTIVE_MEMBER_LIMIT)
        val bookId = db.bookDao().resolveOrCreateId(bookName)
        val book = db.bookDao().getById(bookId) ?: error("无法创建账本身份")
        check(db.sharedLedgerDao().getByBookId(book.id) == null) { "该账本已经是共享账本" }
        val ledgerUuid = UUID.randomUUID().toString()
        val members = memberNames.mapIndexed { index, name -> ManifestMember(UUID.randomUUID().toString(), name.trim(), index + 1) }
        val remotePath = "/shared-ledger/$ledgerUuid"
        val config = SharedWebDavClient.Config(webdavUrl, webdavUser, password)
        webDav.ensureDirectory(config, "$remotePath/operations")
        val manifest = Manifest(sharedBookId = ledgerUuid, name = ledgerName, createdAt = System.currentTimeMillis(), members = members)
        require(ManifestValidator.validate(manifest).isValid) { "共享账本信息无效" }
        webDav.put(config, "$remotePath/meta.json", gson.toJson(manifest))

        db.withTransaction {
            val ledgerId = db.sharedLedgerDao().insert(SharedLedger(
                uuid = ledgerUuid, bookId = book.id, name = ledgerName, webdavUrl = webdavUrl,
                webdavUser = webdavUser, remotePath = remotePath, localMemberId = members.first().memberId,
                createdAt = manifest.createdAt
            ))
            db.sharedMemberDao().insertAll(members.map { SharedMember(ledgerId = ledgerId, memberId = it.memberId, displayName = it.displayName, joinOrder = it.joinOrder, isLocal = it.joinOrder == 1) })
            db.syncStateDao().save(SyncState(ledgerId, DeviceIdManager.getDeviceId(context)))
            seedHistory(ledgerId, ledgerUuid, book.name, book.id, members.first().memberId)
        }
        SharedCredentials.save(context, ledgerUuid, password)
        SharedSyncScheduler.enqueueNow(context)
        return InviteCodec.encode(SharedInvite(ledgerUuid, ledgerName, webdavUrl, webdavUser, remotePath, members[1].memberId, members[1].displayName, 2))
    }

    suspend fun join(invite: SharedInvite, password: String, existingBookName: String? = null): Long {
        check(db.sharedLedgerDao().getByUuid(invite.ledgerId) == null) { "已经加入该共享账本" }
        val config = SharedWebDavClient.Config(invite.webdavUrl, invite.webdavUser, password)
        check(!webDav.exists(config, "${invite.remotePath}/closed.json")) { "该共享账本已解散" }
        var manifest = decodeManifest(webDav.get(config, "${invite.remotePath}/meta.json"))
        require(ManifestValidator.validate(manifest).isValid) { "远端共享账本信息无效" }
        require(manifest.sharedBookId == invite.ledgerId) { "邀请与远端账本不一致" }
        require(manifest.members.any { it.memberId == invite.memberId }) { "邀请成员不存在" }
        val profileName = Prefs.getUserChatName(context).trim()
            .takeIf { it.isNotBlank() && it != "我" }
            ?.take(Manifest.MAX_MEMBER_DISPLAY_NAME_LENGTH)
        if (profileName != null) {
            manifest = manifest.copy(members = manifest.members.map { member ->
                if (member.memberId == invite.memberId) member.copy(displayName = profileName) else member
            })
            require(ManifestValidator.validate(manifest).isValid) { "成员名称无效" }
            webDav.put(config, "${invite.remotePath}/meta.json", gson.toJson(manifest))
        }
        val localName = existingBookName?.trim()?.takeIf { it.isNotBlank() } ?: uniqueBookName(manifest.name)
        val bookId = db.bookDao().resolveOrCreateId(localName)
        check(db.sharedLedgerDao().getByBookId(bookId) == null) { "所选账本已经是共享账本" }
        val ledgerId = db.withTransaction {
            val id = db.sharedLedgerDao().insert(SharedLedger(
                uuid = manifest.sharedBookId, bookId = bookId, name = manifest.name,
                webdavUrl = invite.webdavUrl, webdavUser = invite.webdavUser,
                remotePath = invite.remotePath, localMemberId = invite.memberId, createdAt = manifest.createdAt
            ))
            db.sharedMemberDao().insertAll(manifest.members.map { SharedMember(ledgerId = id, memberId = it.memberId, displayName = it.displayName, joinOrder = it.joinOrder, isLocal = it.memberId == invite.memberId) })
            db.syncStateDao().save(SyncState(id, DeviceIdManager.getDeviceId(context)))
            seedHistory(id, manifest.sharedBookId, localName, bookId, invite.memberId)
            id
        }
        SharedCredentials.save(context, invite.ledgerId, password)
        runCatching { SharedSyncEngine(context, db).syncLedger(ledgerId) }
        return ledgerId
    }

    suspend fun exitKeepingLocalCopy(ledgerId: Long, syncFirst: Boolean = false) {
        val ledger = db.sharedLedgerDao().getById(ledgerId) ?: return
        if (syncFirst) {
            SharedSyncEngine(context, db).syncLedger(ledgerId)
            check(db.syncQueueDao().count(ledgerId) == 0) { "仍有内容未上传，请联网同步后重试" }
        }
        val bookName = db.bookDao().getById(ledger.bookId)?.name ?: ledger.name
        db.withTransaction {
            db.billDao().clearSharedState(bookName)
            db.budgetDao().clearSharedState(ledger.bookId)
            db.sharedLedgerDao().deleteById(ledgerId)
        }
        SharedCredentials.clear(context, ledger.uuid)
    }

    suspend fun dissolve(ledgerId: Long) {
        val ledger = db.sharedLedgerDao().getById(ledgerId) ?: return
        val localMember = db.sharedMemberDao().get(ledgerId, ledger.localMemberId)
        check(localMember?.joinOrder == 1) { "只有创建者可以解散共享账本" }
        SharedSyncEngine(context, db).syncLedger(ledgerId)
        check(db.syncQueueDao().count(ledgerId) == 0) { "仍有内容未上传，请联网同步后重试" }
        val password = SharedCredentials.load(context, ledger.uuid) ?: error("缺少 WebDAV 应用密码")
        val config = SharedWebDavClient.Config(ledger.webdavUrl, ledger.webdavUser, password)
        webDav.put(config, "${ledger.remotePath}/closed.json", gson.toJson(mapOf(
            "sharedBookId" to ledger.uuid,
            "dissolvedBy" to ledger.localMemberId,
            "dissolvedAt" to System.currentTimeMillis()
        )))
        exitKeepingLocalCopy(ledgerId, syncFirst = false)
    }

    suspend fun inviteText(ledgerId: Long): String {
        val ledger = db.sharedLedgerDao().getById(ledgerId) ?: error("共享账本不存在")
        val member = db.sharedMemberDao().getByLedgerId(ledgerId).firstOrNull { !it.isLocal }
            ?: error("没有可邀请的成员")
        return InviteCodec.encode(SharedInvite(
            ledger.uuid, ledger.name, ledger.webdavUrl, ledger.webdavUser, ledger.remotePath,
            member.memberId, member.resolvedName(), member.joinOrder
        ))
    }

    private suspend fun seedHistory(ledgerId: Long, ledgerUuid: String, bookName: String, bookId: Long, memberId: String) {
        val bills = db.billDao().getAllByBookName(bookName).filter { it.isShareable() }
        val idMap = bills.associate { it.id to (it.sharedId ?: UUID.randomUUID().toString()) }
        bills.sortedBy { it.subType == Bill.SUBTYPE_REFUND }.forEach { old ->
            val entityId = idMap.getValue(old.id)
            val icon = old.cateIcon ?: com.taostudio.tapaccounting.CategoryIconHelper
                .findCategoryIcon(context, old.categoryName, old.type).takeIf { it.isNotBlank() }
            val bill = old.copy(sharedId = entityId, memberId = memberId, isShared = true, sharedRevision = 1,
                sharedDeviceId = DeviceIdManager.getDeviceId(context), relatedSharedId = old.relatedBillId?.let(idMap::get))
                .copy(cateIcon = icon)
            db.billDao().updateBill(bill)
            enqueue(ledgerId, ledgerUuid, "bill", entityId, "create", 1, memberId, billPayload(bill))
        }
        db.budgetDao().getAllByBookId(bookId).forEach { old ->
            val slot = old.categoryName?.trim()?.lowercase().orEmpty().ifBlank { "__total__" }
            val entityId = old.sharedId ?: UUID.nameUUIDFromBytes("$ledgerUuid|${old.yearMonth}|$slot".toByteArray()).toString()
            val budget = old.copy(sharedId = entityId, revision = 1, isShared = true, sharedDeviceId = DeviceIdManager.getDeviceId(context))
            db.budgetDao().update(budget)
            enqueue(ledgerId, ledgerUuid, "budget", entityId, "create", 1, memberId, gson.toJsonTree(budget).asJsonObject)
        }
    }

    internal suspend fun enqueue(ledgerId: Long, ledgerUuid: String, type: String, entityId: String, action: String, revision: Long, memberId: String, payload: JsonObject?) {
        val op = Operation(UUID.randomUUID().toString(), action, type, entityId, revision, DeviceIdManager.getDeviceId(context), memberId, System.currentTimeMillis(), payload)
        val json = SharedOperationCodec.encode(op)
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        db.syncOperationDao().insertIgnore(SyncOperation(op.operationId, ledgerId, type, entityId, action, revision, op.deviceId, memberId, json, op.timestamp))
        db.syncQueueDao().insertIgnore(SyncQueue(op.operationId, ledgerId, json, "/shared-ledger/$ledgerUuid/operations/$month/${op.deviceId}/${op.operationId}.json", op.timestamp))
    }

    private fun billPayload(bill: Bill) = gson.toJsonTree(bill.copy(id = 0, accountId = null, toAccountId = null, accountName = "", toAccountName = "", accountBalanceAfter = null, toAccountBalanceAfter = null)).asJsonObject
    internal fun decodeManifest(raw: String): Manifest {
        require(raw.length <= 131_072) { "远端共享账本信息过大" }
        val root = JsonParser.parseString(raw).asJsonObject
        require(StrictJsonParser.parseInt(root.get("schemaVersion"), "schemaVersion") != null)
        require(StrictJsonParser.parseLong(root.get("createdAt"), "createdAt") != null)
        root.getAsJsonArray("members").forEach {
            require(StrictJsonParser.parseInt(it.asJsonObject.get("joinOrder"), "joinOrder") != null)
        }
        return gson.fromJson(root, Manifest::class.java)
    }
    private suspend fun uniqueBookName(name: String): String {
        if (db.bookDao().getByName(name) == null) return name
        var index = 2
        while (db.bookDao().getByName("$name ($index)") != null) index++
        return "$name ($index)"
    }

    companion object {
        fun Bill.isShareable() = type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME) && subType !in setOf(Bill.SUBTYPE_BALANCE_ADJUSTMENT, Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED) && type != Bill.TYPE_REPAYMENT
    }
}
