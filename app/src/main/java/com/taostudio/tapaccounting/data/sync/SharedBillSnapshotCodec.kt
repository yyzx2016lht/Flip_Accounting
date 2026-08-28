package com.taostudio.tapaccounting.data.sync

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.sync.protocol.Operation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 一个成员在一个共享账本中的完整账单快照。成员之间使用独立文件，互不覆盖。 */
internal data class SharedBillSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val ledgerId: String,
    val memberId: String,
    val deviceId: String,
    val generatedAt: Long,
    val bills: List<Bill>
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

internal object SharedBillSnapshotCodec {
    private const val MAX_BILLS = 50_000
    private const val MAX_COMPRESSED_BYTES = 32 * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 128 * 1024 * 1024
    private val gson = Gson()

    fun encode(snapshot: SharedBillSnapshot): ByteArray {
        require(isValid(snapshot)) { "共享账单快照无效" }
        val raw = gson.toJson(snapshot).toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_UNCOMPRESSED_BYTES) { "共享账单快照过大" }
        return ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(raw) }
            output.toByteArray().also {
                require(it.size <= MAX_COMPRESSED_BYTES) { "共享账单快照过大" }
            }
        }
    }

    fun decode(bytes: ByteArray): SharedBillSnapshot? = runCatching {
        require(bytes.size <= MAX_COMPRESSED_BYTES)
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= MAX_UNCOMPRESSED_BYTES)
                output.write(buffer, 0, read)
            }
        }
        val raw = output.toString(Charsets.UTF_8.name())
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.get("schemaVersion")?.asInt == SharedBillSnapshot.CURRENT_SCHEMA_VERSION)
        gson.fromJson(root, SharedBillSnapshot::class.java).takeIf(::isValid)
    }.getOrNull()

    private fun isValid(snapshot: SharedBillSnapshot): Boolean {
        if (snapshot.schemaVersion != SharedBillSnapshot.CURRENT_SCHEMA_VERSION ||
            !Operation.UUID_PATTERN.matches(snapshot.ledgerId) ||
            !Operation.UUID_PATTERN.matches(snapshot.memberId) ||
            !Operation.UUID_PATTERN.matches(snapshot.deviceId) ||
            snapshot.generatedAt < 0 || snapshot.bills.size > MAX_BILLS
        ) return false

        val ids = HashSet<String>(snapshot.bills.size)
        return snapshot.bills.all { bill ->
            val sharedId = bill.sharedId
            sharedId != null && Operation.UUID_PATTERN.matches(sharedId) && ids.add(sharedId) &&
                bill.memberId == snapshot.memberId && bill.isShared &&
                bill.type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME) &&
                bill.subType in setOf(Bill.SUBTYPE_NORMAL, Bill.SUBTYPE_REFUND) &&
                bill.amount.isFinite() && bill.amount >= 0 && bill.time >= 0 &&
                (bill.relatedSharedId == null || Operation.UUID_PATTERN.matches(bill.relatedSharedId))
        }
    }
}
