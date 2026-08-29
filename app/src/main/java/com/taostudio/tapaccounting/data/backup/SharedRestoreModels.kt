package com.taostudio.tapaccounting.data.backup

import com.google.gson.JsonParser
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.Book
import com.taostudio.tapaccounting.data.local.entity.Budget
import com.taostudio.tapaccounting.data.local.entity.SharedLedger
import com.taostudio.tapaccounting.data.local.entity.SharedMember
import com.taostudio.tapaccounting.data.local.entity.SyncOperation
import com.taostudio.tapaccounting.data.local.entity.SyncQueue
import com.taostudio.tapaccounting.data.sync.SharedOperationCodec
import com.taostudio.tapaccounting.data.sync.protocol.Operation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Controls whether restored shared data becomes private data or reconnects to its remote ledger. */
enum class SharedRestoreMode {
    /** Safe default: retain business data, but remove every shared marker and connection. */
    LOCAL_COPY,

    /** Recreate the ledger descriptors, members, and pending local outbox. */
    RECONNECT
}

/** Whether an encrypted backup can resume the same shared-member seats on another device. */
enum class SharedRecoveryReadiness {
    NOT_PRESENT,
    READY,
    INCOMPLETE
}

/** Portable shared-ledger descriptor. Room-local book and ledger IDs are deliberately excluded. */
data class SharedLedgerBackup(
    val uuid: String,
    val bookName: String,
    val name: String,
    val webdavUrl: String,
    val webdavUser: String,
    val remotePath: String,
    val localMemberId: String,
    val createdAt: Long
)

/** Portable member record, linked through the stable ledger UUID rather than a Room ID. */
data class SharedMemberBackup(
    val ledgerUuid: String,
    val memberId: String,
    val displayName: String,
    val joinOrder: Int,
    val isLocal: Boolean
)

/**
 * Durable part of an outbox row. Retry counters, the last error, and the old-device path are
 * runtime state and are intentionally not backed up.
 */
data class PendingSyncQueueBackup(
    val operationId: String,
    val ledgerUuid: String,
    val createdAt: Long
)

/**
 * Operation data required to rebuild both the local winner record and its queued wire payload.
 * The old device ID is deliberately excluded and must be supplied by the restore caller.
 */
data class PendingSyncOperationBackup(
    val operationId: String,
    val ledgerUuid: String,
    val action: String,
    val entityType: String,
    val entityId: String,
    val revision: Long,
    val memberId: String,
    val timestamp: Long,
    val payloadJson: String?
)

data class SharedRestoreData(
    val ledgers: List<SharedLedgerBackup> = emptyList(),
    val members: List<SharedMemberBackup> = emptyList(),
    val pendingQueue: List<PendingSyncQueueBackup> = emptyList(),
    val pendingOperations: List<PendingSyncOperationBackup> = emptyList()
)

internal fun assessSharedRecoveryReadiness(
    data: SharedRestoreData?,
    credentialLedgerUuids: Set<String>
): SharedRecoveryReadiness {
    if (data == null || data.ledgers.isEmpty()) return SharedRecoveryReadiness.NOT_PRESENT

    val ledgerUuids = data.ledgers.mapTo(linkedSetOf(), SharedLedgerBackup::uuid)
    val hasUniqueLedgers = ledgerUuids.size == data.ledgers.size
    val hasEveryCredential = credentialLedgerUuids == ledgerUuids
    val hasEveryLocalMember = data.ledgers.all { ledger ->
        val localMembers = data.members.filter { member ->
            member.ledgerUuid == ledger.uuid && member.isLocal
        }
        localMembers.size == 1 && localMembers.single().memberId == ledger.localMemberId
    }
    return if (hasUniqueLedgers && hasEveryCredential && hasEveryLocalMember) {
        SharedRecoveryReadiness.READY
    } else {
        SharedRecoveryReadiness.INCOMPLETE
    }
}

/** Validates the portable DTO boundary used by the generic backup snapshot map. */
internal fun requireSharedLedgerBackups(snapshotValue: Any?): List<SharedLedgerBackup> {
    val records = snapshotValue as? List<*>
        ?: throw IllegalArgumentException("共享账本备份模块类型不正确")
    return records.mapIndexed { index, record ->
        requireNotNull(record as? SharedLedgerBackup) {
            "共享账本备份记录 $index 类型不正确"
        }
    }
}

internal fun buildSharedRestoreData(
    books: List<Book>,
    ledgers: List<SharedLedger>,
    members: List<SharedMember>,
    queue: List<SyncQueue>,
    pendingOperations: List<SyncOperation>
): SharedRestoreData {
    val bookById = books.associateBy(Book::id)
    val ledgerById = ledgers.associateBy(SharedLedger::id)
    require(bookById.size == books.size) { "账本快照包含重复 ID" }
    require(ledgerById.size == ledgers.size) { "共享账本快照包含重复 ID" }

    val portableLedgers = ledgers.map { ledger ->
        val book = requireNotNull(bookById[ledger.bookId]) {
            "共享账本 ${ledger.uuid} 缺少对应账本"
        }
        SharedLedgerBackup(
            uuid = ledger.uuid,
            bookName = book.name,
            name = ledger.name,
            webdavUrl = ledger.webdavUrl,
            webdavUser = ledger.webdavUser,
            remotePath = ledger.remotePath,
            localMemberId = ledger.localMemberId,
            createdAt = ledger.createdAt
        )
    }

    val portableMembers = members.map { member ->
        val ledger = requireNotNull(ledgerById[member.ledgerId]) {
            "共享成员 ${member.memberId} 缺少对应账本"
        }
        SharedMemberBackup(
            ledgerUuid = ledger.uuid,
            memberId = member.memberId,
            displayName = member.displayName,
            joinOrder = member.joinOrder,
            isLocal = member.isLocal
        )
    }

    val operationById = pendingOperations.associateBy(SyncOperation::operationId)
    require(operationById.size == pendingOperations.size) { "待上传操作包含重复 ID" }
    require(operationById.keys == queue.mapTo(mutableSetOf(), SyncQueue::operationId)) {
        "待上传队列与本地操作记录不完整"
    }

    val portableQueue = ArrayList<PendingSyncQueueBackup>(queue.size)
    val portableOperations = ArrayList<PendingSyncOperationBackup>(queue.size)
    queue.forEach { queued ->
        val ledger = requireNotNull(ledgerById[queued.ledgerId]) {
            "待上传操作 ${queued.operationId} 缺少对应共享账本"
        }
        val stored = requireNotNull(operationById[queued.operationId]) {
            "待上传操作 ${queued.operationId} 缺少本地操作记录"
        }
        val operation = requireNotNull(SharedOperationCodec.decode(queued.operationJson)) {
            "待上传操作 ${queued.operationId} 的协议数据无效"
        }
        require(stored.ledgerId == queued.ledgerId && operationMatches(stored, operation)) {
            "待上传操作 ${queued.operationId} 的队列与操作记录不一致"
        }
        portableQueue += PendingSyncQueueBackup(
            operationId = operation.operationId,
            ledgerUuid = ledger.uuid,
            createdAt = queued.createdAt
        )
        portableOperations += PendingSyncOperationBackup(
            operationId = operation.operationId,
            ledgerUuid = ledger.uuid,
            action = operation.type,
            entityType = operation.entityType,
            entityId = operation.entityId,
            revision = operation.revision,
            memberId = operation.memberId,
            timestamp = operation.timestamp,
            payloadJson = portablePayload(operation)?.toString()
        )
    }

    return SharedRestoreData(
        ledgers = portableLedgers,
        members = portableMembers,
        pendingQueue = portableQueue,
        pendingOperations = portableOperations
    )
}

internal fun sanitizeBillForRestore(bill: Bill, mode: SharedRestoreMode): Bill =
    if (mode == SharedRestoreMode.RECONNECT) bill else bill.copy(
        sharedId = null,
        memberId = null,
        isShared = false,
        sharedRevision = 0,
        sharedDeviceId = null,
        relatedSharedId = null
    )

internal fun sanitizeBudgetForRestore(budget: Budget, mode: SharedRestoreMode): Budget =
    if (mode == SharedRestoreMode.RECONNECT) budget else budget.copy(
        sharedId = null,
        revision = 0,
        isShared = false,
        sharedDeviceId = null,
        memberBudgetAllocations = null
    )

/** Validate the complete reconnect snapshot before any table is changed. */
internal fun validateSharedReconnect(
    data: SharedRestoreData,
    newDeviceId: String,
    sharedBookNames: Set<String>
) {
    require(Operation.UUID_PATTERN.matches(newDeviceId)) { "恢复共享账本需要新的有效设备 ID" }

    val ledgerByUuid = data.ledgers.associateBy(SharedLedgerBackup::uuid)
    require(ledgerByUuid.size == data.ledgers.size) { "共享账本 UUID 重复" }
    require(data.ledgers.map(SharedLedgerBackup::bookName).toSet().size == data.ledgers.size) {
        "一个本地账本不能连接多个共享账本"
    }
    data.ledgers.forEach { ledger ->
        require(Operation.UUID_PATTERN.matches(ledger.uuid)) { "共享账本 UUID 无效" }
        require(ledger.bookName.isNotBlank()) { "共享账本缺少本地账本名称" }
        require(ledger.remotePath == "/shared-ledger/${ledger.uuid}") { "共享账本远端路径无效" }
        require(Operation.UUID_PATTERN.matches(ledger.localMemberId)) { "本地成员 ID 无效" }
    }
    require(sharedBookNames.all { bookName -> data.ledgers.any { it.bookName == bookName } }) {
        "共享业务数据缺少对应的共享账本描述"
    }

    val membersByLedger = data.members.groupBy(SharedMemberBackup::ledgerUuid)
    data.members.forEach { member ->
        require(member.ledgerUuid in ledgerByUuid) { "共享成员缺少对应账本" }
        require(Operation.UUID_PATTERN.matches(member.memberId)) { "共享成员 ID 无效" }
    }
    data.ledgers.forEach { ledger ->
        val ledgerMembers = membersByLedger[ledger.uuid].orEmpty()
        require(ledgerMembers.map(SharedMemberBackup::memberId).toSet().size == ledgerMembers.size) {
            "共享账本成员重复"
        }
        require(ledgerMembers.count(SharedMemberBackup::isLocal) == 1) { "共享账本必须且只能有一个本地成员" }
        require(ledgerMembers.single(SharedMemberBackup::isLocal).memberId == ledger.localMemberId) {
            "共享账本本地成员不一致"
        }
    }

    val queueById = data.pendingQueue.associateBy(PendingSyncQueueBackup::operationId)
    val operationById = data.pendingOperations.associateBy(PendingSyncOperationBackup::operationId)
    require(queueById.size == data.pendingQueue.size && operationById.size == data.pendingOperations.size) {
        "待上传操作 ID 重复"
    }
    require(queueById.keys == operationById.keys) { "待上传队列与操作记录不完整" }
    data.pendingOperations.forEach { operation ->
        require(operation.ledgerUuid in ledgerByUuid) { "待上传操作缺少对应共享账本" }
        require(queueById.getValue(operation.operationId).ledgerUuid == operation.ledgerUuid) {
            "待上传队列与操作账本不一致"
        }
        require(membersByLedger[operation.ledgerUuid].orEmpty().any { it.memberId == operation.memberId }) {
            "待上传操作的成员不在共享账本中"
        }
        // Materialisation also validates action/entity/revision/timestamp/payload through the wire codec.
        materializePendingOperation(
            operation = operation,
            queue = queueById.getValue(operation.operationId),
            ledgerId = 1L,
            ledgerRemotePath = ledgerByUuid.getValue(operation.ledgerUuid).remotePath,
            newDeviceId = newDeviceId
        )
    }
}

internal fun materializePendingOperation(
    operation: PendingSyncOperationBackup,
    queue: PendingSyncQueueBackup,
    ledgerId: Long,
    ledgerRemotePath: String,
    newDeviceId: String
): Pair<SyncOperation, SyncQueue> {
    require(queue.operationId == operation.operationId && queue.ledgerUuid == operation.ledgerUuid) {
        "待上传队列与操作记录不一致"
    }
    val payload = operation.payloadJson?.let { raw ->
        runCatching { JsonParser.parseString(raw).asJsonObject }
            .getOrElse { throw IllegalArgumentException("待上传操作 payload 无效", it) }
    }
    val wire = Operation(
        operationId = operation.operationId,
        type = operation.action,
        entityType = operation.entityType,
        entityId = operation.entityId,
        revision = operation.revision,
        deviceId = newDeviceId,
        memberId = operation.memberId,
        timestamp = operation.timestamp,
        payload = payload
    )
    val encoded = SharedOperationCodec.encode(wire)
    require(SharedOperationCodec.decode(encoded) != null) { "待上传操作协议数据无效" }
    val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(operation.timestamp))
    return SyncOperation(
        operationId = operation.operationId,
        ledgerId = ledgerId,
        entityType = operation.entityType,
        entityId = operation.entityId,
        action = operation.action,
        revision = operation.revision,
        deviceId = newDeviceId,
        memberId = operation.memberId,
        payload = encoded,
        appliedAt = operation.timestamp
    ) to SyncQueue(
        operationId = operation.operationId,
        ledgerId = ledgerId,
        operationJson = encoded,
        remotePath = "$ledgerRemotePath/operations/$month/$newDeviceId/${operation.operationId}.json",
        createdAt = queue.createdAt,
        retryCount = 0,
        lastError = null
    )
}

internal fun requireSharedRestoreGuard(hasActiveSharedLedgers: Boolean, hasSelectedDatabaseModule: Boolean) {
    require(!hasActiveSharedLedgers || !hasSelectedDatabaseModule) {
        "检测到正在同步的共享账本。请先退出共享，再恢复任何数据库模块，避免部分覆盖共享数据"
    }
}

private fun operationMatches(stored: SyncOperation, wire: Operation): Boolean =
    stored.operationId == wire.operationId &&
        stored.entityType == wire.entityType &&
        stored.entityId == wire.entityId &&
        stored.action == wire.type &&
        stored.revision == wire.revision &&
        stored.deviceId == wire.deviceId &&
        stored.memberId == wire.memberId

private fun portablePayload(operation: Operation) = operation.payload?.deepCopy()?.apply {
    // These are Room-local identities or runtime winner metadata. The receiver reconstructs them.
    val keysToRemove = when (operation.entityType) {
        "bill" -> listOf(
            "id",
            "categoryId",
            "accountId",
            "toAccountId",
            "relatedBillId",
            "accountBalanceAfter",
            "toAccountBalanceAfter",
            "sharedDeviceId"
        )
        "budget" -> {
            // The wire reader only needs zero (total budget) versus non-zero (category budget).
            val portableCategoryKey = if (get("categoryKey")?.asLong == Budget.TOTAL_CATEGORY_KEY) {
                Budget.TOTAL_CATEGORY_KEY
            } else {
                -1L
            }
            addProperty("categoryKey", portableCategoryKey)
            listOf("id", "bookId", "categoryId", "sharedDeviceId")
        }
        else -> emptyList()
    }
    keysToRemove.forEach { key -> remove(key) }
}
