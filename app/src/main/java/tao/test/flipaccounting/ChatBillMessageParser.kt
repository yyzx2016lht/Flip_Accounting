package tao.test.flipaccounting

import org.json.JSONArray
import org.json.JSONObject
import tao.test.flipaccounting.data.local.entity.Bill

object ChatBillMessageParser {

    private const val DEPRECATED_BILL_IDS_PREFIX = "__deprecated__:"

    fun parseBillIds(json: String): List<Long> {
        if (json.isBlank()) return emptyList()
        val cleanJson = json.removePrefix(DEPRECATED_BILL_IDS_PREFIX)
        return try {
            val arr = JSONArray(cleanJson)
            (0 until arr.length()).map { arr.getString(it).toLong() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isDeprecatedBillMessage(billIds: String): Boolean =
        billIds.startsWith(DEPRECATED_BILL_IDS_PREFIX)

    fun markBillIdsAsDeprecated(billIds: String): String {
        if (billIds.startsWith(DEPRECATED_BILL_IDS_PREFIX)) return billIds
        return DEPRECATED_BILL_IDS_PREFIX + billIds
    }

    fun parseBillsFromMessageContent(
        content: String,
        currentBookName: String,
        parseTimeToMillis: (String) -> Long
    ): List<Bill> {
        if (content.isBlank()) return emptyList()
        return try {
            val root = JSONObject(content)
            val arr = root.optJSONArray("bills") ?: JSONArray()
            parseBillArray(arr, currentBookName, parseTimeToMillis)
        } catch (_: Exception) {
            try {
                val arr = JSONArray(content)
                parseBillArray(arr, currentBookName, parseTimeToMillis)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun parseBillArray(
        arr: JSONArray,
        currentBookName: String,
        parseTimeToMillis: (String) -> Long
    ): List<Bill> =
        (0 until arr.length()).mapNotNull { index ->
            val billJson = arr.optJSONObject(index) ?: return@mapNotNull null
            val rawType = billJson.optInt("type", 0)
            val rawSubType = billJson.optInt("subType", Bill.SUBTYPE_NORMAL)
            val finalType = if (rawType == 3) 2 else rawType
            val subType = if (rawType == 3) Bill.SUBTYPE_REPAYMENT else rawSubType
            Bill(
                id = billJson.optLong("id", 0L),
                type = finalType,
                subType = subType,
                amount = billJson.optDouble("amount", 0.0),
                originalAmount = billJson.optDouble("originalAmount", billJson.optDouble("amount", 0.0)),
                currency = billJson.optString("currency", "CNY"),
                exchangeRate = billJson.optDouble("exchangeRate", 1.0),
                categoryName = billJson.optString("category_name", "其它").replace("/::/", " > "),
                accountName = billJson.optString("asset_name", ""),
                toAccountName = billJson.optString("to_asset_name", ""),
                time = parseTimeToMillis(billJson.optString("time", "")),
                remark = billJson.optString("remarks", billJson.optString("remark", "")),
                bookName = currentBookName,
                relatedBillId = billJson.optLong("relatedBillId", 0L).takeIf { it > 0L }
            )
        }

    fun parseDeprecatedBillIdsFromContent(content: String): Set<Long> {
        if (content.isBlank()) return emptySet()
        return try {
            val root = JSONObject(content)
            val arr = root.optJSONArray("deprecatedBillIds") ?: return emptySet()
            buildSet {
                for (i in 0 until arr.length()) {
                    val id = arr.optLong(i, 0L)
                    if (id != 0L) add(id)
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun parseEditedBillIdsFromContent(content: String): Set<Long> {
        if (content.isBlank()) return emptySet()
        return try {
            val root = JSONObject(content)
            val arr = root.optJSONArray("editedBillIds") ?: return emptySet()
            buildSet {
                for (i in 0 until arr.length()) {
                    val id = arr.optLong(i, 0L)
                    if (id > 0L) add(id)
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun parseSnapshotOnlyFromContent(content: String): Boolean {
        if (content.isBlank()) return false
        return try {
            JSONObject(content).optBoolean("snapshotOnly", false)
        } catch (_: Exception) {
            false
        }
    }

    fun mergeChatBillSnapshots(liveBills: List<Bill>, snapshots: List<Bill>): List<Bill> {
        if (snapshots.isEmpty()) return liveBills
        val liveById = liveBills.filter { it.id > 0L }.associateBy { it.id }
        val merged = mutableListOf<Bill>()
        snapshots.forEach { snapshot ->
            val live = if (snapshot.id > 0L) liveById[snapshot.id] else null
            merged += live ?: snapshot
        }
        liveBills.forEach { live ->
            if (live.id <= 0L || snapshots.none { it.id == live.id }) {
                merged += live
            }
        }
        return merged
    }

    fun buildBillMessageContent(
        bills: List<Bill>,
        formatTime: (Long) -> String,
        deprecatedBillIds: Set<Long> = emptySet(),
        editedBillIds: Set<Long> = emptySet(),
        snapshotOnly: Boolean = false
    ): String {
        val arr = JSONArray()
        bills.forEach { bill ->
            arr.put(JSONObject().apply {
                put("id", bill.id)
                put("amount", bill.amount)
                put("type", if (bill.subType == Bill.SUBTYPE_REPAYMENT) 3 else bill.type)
                put("subType", bill.subType)
                put("originalAmount", bill.originalAmount)
                put("asset_name", bill.accountName)
                put("category_name", bill.categoryName.replace(" > ", "/::/"))
                put("time", formatTime(bill.time))
                put("remarks", bill.remark)
                put("currency", bill.currency)
                put("exchangeRate", bill.exchangeRate)
                put("to_asset_name", bill.toAccountName)
                put("fee", bill.fee)
                if (bill.relatedBillId != null) {
                    put("relatedBillId", bill.relatedBillId)
                }
            })
        }
        return JSONObject().apply {
            put("bills", arr)
            put("deprecatedBillIds", JSONArray(deprecatedBillIds.toList()))
            put("editedBillIds", JSONArray(editedBillIds.toList()))
            put("snapshotOnly", snapshotOnly)
        }.toString()
    }
}
