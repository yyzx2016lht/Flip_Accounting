package com.taostudio.tapaccounting

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill as DbBill
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.taostudio.tapaccounting.logic.CurrencyManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal data class AIAccountingPromptContext(
    val dbAssets: List<Asset>,
    val assetInfoList: List<Map<String, String>>,
    val assetNames: List<String>,
    val assetCurrencyMap: Map<String, String>,  // asset name → currency code
    val expenseCats: List<String>,
    val incomeCats: List<String>,
    val currencies: List<String>,
    val currentTimeStr: String,
    val assetFeatureEnabled: Boolean,
    val availableBooks: List<String>
)

internal suspend fun buildAccountingPromptContext(ctx: Context): AIAccountingPromptContext {
    val assetFeatureEnabled = Prefs.isAssetFeatureEnabled(ctx)
    val db = AppDatabase.getDatabase(ctx)
    val dbAssets = withContext(Dispatchers.IO) {
        db.assetDao().getAllAssetsList()
    }.filterNot { it.isArchived }
    val assetInfoList = if (assetFeatureEnabled) {
        dbAssets.map { asset ->
            mapOf(
                "name" to asset.name,
                "category" to if (asset.assetCategory == Asset.CATEGORY_CREDIT_CARD) "credit_card" else "normal",
                "currency" to asset.currency.ifEmpty { "CNY" }
            )
        }
    } else {
        emptyList()
    }
    val assetNames = if (assetFeatureEnabled) {
        dbAssets.map { it.name }.ifEmpty { Prefs.getAssets(ctx).map { it.name } }
    } else {
        emptyList()
    }
    val assetCurrencyMap = if (assetFeatureEnabled) {
        dbAssets.filter { it.currency.isNotEmpty() && it.currency != "CNY" }
            .associate { it.name to it.currency }
    } else {
        emptyMap()
    }
    val catRepo = CategoryRepository(db.categoryDao())
    val expenseCats = buildCategoryOptions(withContext(Dispatchers.IO) { catRepo.getCategoryTree(0) })
    val incomeCats = buildCategoryOptions(withContext(Dispatchers.IO) { catRepo.getCategoryTree(1) })
    val now = Date()
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val weekFormat = SimpleDateFormat("EEEE", Locale.getDefault())
    val currentTimeStr = "${timeFormat.format(now)} (${weekFormat.format(now)})"
    val availableBooks = withContext(Dispatchers.IO) {
        val dbBookNames = db.billDao().getAllBookNames()
        BookAccountManager.getBookAccounts(ctx, dbBookNames)
            .map { BookAccountManager.normalizeBookName(it) }
            .filter {
                it.isNotBlank() &&
                    it != BookAccountManager.ALL_BOOK &&
                    it != BookAccountManager.COLLAPSED_BOOK_GROUP
            }
            .distinct()
    }

    return AIAccountingPromptContext(
        dbAssets = dbAssets,
        assetInfoList = assetInfoList,
        assetNames = assetNames,
        assetCurrencyMap = assetCurrencyMap,
        expenseCats = expenseCats,
        incomeCats = incomeCats,
        currencies = CurrencyManager.getEnabledCurrencies(ctx),
        currentTimeStr = currentTimeStr,
        assetFeatureEnabled = assetFeatureEnabled,
        availableBooks = availableBooks
    )
}

private fun buildCategoryOptions(tree: List<CategoryNode>): List<String> = buildList {
    tree.forEach { parentNode ->
        add(parentNode.name)
        parentNode.subs.forEach { childNode ->
            add("${parentNode.name} - ${childNode.name}")
        }
    }
}

internal fun normalizeAccountingResult(
    root: JSONObject,
    expenseCats: List<String>,
    incomeCats: List<String>,
    assetNames: List<String>,
    assetFeatureEnabled: Boolean,
    referenceText: String = "",
    nowMillis: Long = System.currentTimeMillis(),
    assetCurrencyMap: Map<String, String> = emptyMap(),
    availableBooks: List<String> = emptyList()
) {
    val batchBookName = if (root.has("bills")) {
        normalizeAccountingBookFields(root, availableBooks)
    } else {
        null
    }

    fun normalizeBillJson(bill: JSONObject, index: Int) {
        val billBookName = normalizeAccountingBookFields(bill, availableBooks)
        if (billBookName == null && batchBookName != null) {
            bill.put("book_name", batchBookName)
        }

        val rawType = bill.optInt("type", 0)
        val type = normalizeBillType(rawType)
        bill.put("type", type)
        normalizeBillTime(bill, referenceText, nowMillis, index)

        // 币种自动继承：资产绑定了非 CNY 币种时，自动覆盖
        inheritAssetCurrency(bill, assetCurrencyMap)

        if (type == DbBill.TYPE_TRANSFER) {
            bill.remove("category_id")
            if (rawType == 3 || bill.optInt("subType", 0) == DbBill.SUBTYPE_REPAYMENT || bill.optString("category_name") == "还款") {
                bill.put("subType", DbBill.SUBTYPE_REPAYMENT)
                bill.put("category_name", "还款")
            } else if (bill.optString("category_name").isBlank()) {
                bill.put("category_name", "转账")
            }
            return
        }

        val candidates = if (type == DbBill.TYPE_INCOME) incomeCats else expenseCats
        if (bill.has("category_id")) {
            val categoryFromId = resolvePromptCategoryId(
                categoryId = bill.optString("category_id", "").trim(),
                type = type,
                expenseCats = expenseCats,
                incomeCats = incomeCats
            )
            bill.remove("category_id")
            bill.put("category_name", categoryFromId ?: resolveOtherCategory(candidates).orEmpty())
            return
        }

        // 兼容旧模型响应和历史调用方；新提示词以 category_id 为准。
        val rawCategory = bill.optString("category_name", "")
        val normalizedCategory = rawCategory
            .replace(" > ", " - ")
            .replace("/::/", " - ")
            .replace(" / ", " - ")
            .trim()
        val matched = findBestMatch(normalizedCategory, candidates)
        if (matched != null) {
            bill.put("category_name", matched)
        } else if (normalizedCategory.isNotEmpty()) {
            bill.put("category_name", resolveOtherCategory(candidates).orEmpty())
        }
    }

    if (root.has("bills")) {
        val bills = root.getJSONArray("bills")
        for (i in 0 until bills.length()) {
            normalizeBillJson(bills.getJSONObject(i), i)
        }
    } else if (root.has("amount")) {
        normalizeBillJson(root, 0)
    }

    if (assetFeatureEnabled) {
        normalizeMisplacedAssetOnExpenseOrIncome(root, assetNames)
        enforceTransferRequiresValidAssets(root, assetNames, expenseCats)
    } else {
        enforceNoAssetMode(root)
    }
}

internal fun resolvePromptCategoryId(
    categoryId: String,
    type: Int,
    expenseCats: List<String>,
    incomeCats: List<String>
): String? {
    val isIncome = type == DbBill.TYPE_INCOME
    val prefix = if (isIncome) INCOME_CATEGORY_ID_PREFIX else EXPENSE_CATEGORY_ID_PREFIX
    if (!categoryId.startsWith(prefix)) return null
    val index = categoryId.removePrefix(prefix).toIntOrNull() ?: return null
    val candidates = if (isIncome) incomeCats else expenseCats
    return candidates.getOrNull(index)
}

internal fun resolvePromptBookId(bookId: String, availableBooks: List<String>): String? {
    if (!bookId.startsWith(BOOK_ID_PREFIX)) return null
    val index = bookId.removePrefix(BOOK_ID_PREFIX).toIntOrNull() ?: return null
    return availableBooks.getOrNull(index)
}

internal fun resolveAccountingBookSelection(
    bookId: String,
    bookName: String,
    availableBooks: List<String>
): String? {
    val normalizedBooks = availableBooks
        .map { BookAccountManager.normalizeBookName(it) }
        .filter {
            it.isNotBlank() &&
                it != BookAccountManager.ALL_BOOK &&
                it != BookAccountManager.COLLAPSED_BOOK_GROUP
        }
        .distinct()
    if (normalizedBooks.isEmpty()) return null

    resolvePromptBookId(bookId.trim(), normalizedBooks)?.let { return it }
    val rawName = bookName.trim()
    if (rawName.isEmpty() || rawName.equals("null", ignoreCase = true)) return null
    val normalizedName = BookAccountManager.normalizeBookName(rawName)
    return normalizedBooks.firstOrNull { it == normalizedName }
}

internal fun resolveAccountingBookForSave(
    billBookName: String,
    batchBookName: String,
    availableBooks: List<String>,
    fallbackBookName: String
): String = resolveAccountingBookSelection("", billBookName, availableBooks)
    ?: resolveAccountingBookSelection("", batchBookName, availableBooks)
    ?: fallbackBookName

private fun normalizeAccountingBookFields(
    json: JSONObject,
    availableBooks: List<String>
): String? {
    val resolved = resolveAccountingBookSelection(
        bookId = json.optString("book_id", ""),
        bookName = json.optString("book_name", ""),
        availableBooks = availableBooks
    )
    json.remove("book_id")
    if (resolved == null) {
        json.remove("book_name")
    } else {
        json.put("book_name", resolved)
    }
    return resolved
}

/**
 * 资产币种自动继承：当 asset_name 匹配到绑定了非 CNY 币种的资产时，自动覆盖 currency。
 * 对 transfer 类型同时处理 to_asset_name。
 */
private fun inheritAssetCurrency(bill: JSONObject, assetCurrencyMap: Map<String, String>) {
    if (assetCurrencyMap.isEmpty()) return
    val fromAsset = bill.optString("asset_name", "").trim()
    if (fromAsset.isNotBlank()) {
        assetCurrencyMap[fromAsset]?.let { bill.put("currency", it) }
    }
    val toAsset = bill.optString("to_asset_name", "").trim()
    if (toAsset.isNotBlank()) {
        // transfer 的 to_asset_name 币种写入 target_currency（跨币种转账场景）
        assetCurrencyMap[toAsset]?.let {
            if (!bill.has("target_currency") || bill.optString("target_currency", "").isBlank()) {
                bill.put("target_currency", it)
            }
        }
    }
}

private val accountingDateTimeFormat: SimpleDateFormat
    get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { isLenient = false }

private fun normalizeBillTime(
    bill: JSONObject,
    referenceText: String,
    nowMillis: Long,
    index: Int
) {
    val explicitDate = extractAccountingDate(referenceText, nowMillis)
    val rawTime = bill.optString("time", "").trim()
    val parsedExisting = parseAccountingDateTime(rawTime)
    val normalizedTime = when {
        explicitDate != null -> mergeDateWithTime(explicitDate, parsedExisting ?: Date(nowMillis), index)
        parsedExisting != null -> parsedExisting
        else -> Date(nowMillis + index * 1000L)
    }
    bill.put("time", accountingDateTimeFormat.format(normalizedTime))
}

private fun parseAccountingDateTime(value: String): Date? =
    runCatching { accountingDateTimeFormat.parse(value) }.getOrNull()

private fun mergeDateWithTime(dateOnly: Calendar, timeSource: Date, index: Int): Date {
    val time = Calendar.getInstance().apply { time = timeSource }
    return (dateOnly.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, time.get(Calendar.HOUR_OF_DAY))
        set(Calendar.MINUTE, time.get(Calendar.MINUTE))
        set(Calendar.SECOND, time.get(Calendar.SECOND) + index)
        set(Calendar.MILLISECOND, 0)
    }.time
}

private fun extractAccountingDate(text: String, nowMillis: Long): Calendar? {
    if (text.isBlank()) return null
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val normalized = text.trim()

    when {
        normalized.contains("前天") -> return dayOffset(now, -2)
        normalized.contains("昨天") -> return dayOffset(now, -1)
        normalized.contains("今天") || normalized.contains("刚刚") || normalized.contains("现在") -> return dayOffset(now, 0)
    }

    val withYear = Regex("""(?<!\d)((?:19|20)\d{2})\s*(?:年|[./-])\s*(\d{1,2})\s*(?:月|[./-])\s*(\d{1,2})\s*(?:日|号)?""")
        .find(normalized)
    if (withYear != null) {
        val year = withYear.groupValues[1].toIntOrNull()
        val month = withYear.groupValues[2].toIntOrNull()
        val day = withYear.groupValues[3].toIntOrNull()
        buildValidDate(year, month, day)?.let { return it }
    }

    val withoutYearPatterns = listOf(
        Regex("""(?<!\d)(\d{1,2})\s*月\s*(\d{1,2})\s*(?:日|号)?"""),
        Regex("""(?<!\d)(\d{1,2})\s*[./-]\s*(\d{1,2})\s*(?:日|号)"""),
        Regex("""(?<!\d)(\d{1,2})\s*-\s*(\d{1,2})(?!\d)""")
    )
    withoutYearPatterns.forEach { pattern ->
        val match = pattern.find(normalized) ?: return@forEach
        if (looksLikeAmountSuffix(normalized, match.range.last + 1)) return@forEach
        val month = match.groupValues[1].toIntOrNull()
        val day = match.groupValues[2].toIntOrNull()
        buildValidDate(now.get(Calendar.YEAR), month, day)?.let { return it }
    }
    return null
}

private fun dayOffset(base: Calendar, offset: Int): Calendar =
    (base.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, offset)
        set(Calendar.MILLISECOND, 0)
    }

private fun buildValidDate(year: Int?, month: Int?, day: Int?): Calendar? {
    if (year == null || month == null || day == null) return null
    if (month !in 1..12 || day !in 1..31) return null
    return runCatching {
        Calendar.getInstance().apply {
            isLenient = false
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            time
        }
    }.getOrNull()
}

private fun looksLikeAmountSuffix(text: String, index: Int): Boolean {
    if (index !in text.indices) return false
    return text[index] in setOf('元', '块', '毛', '角', '￥', '¥', '$', '€')
}

internal fun summarizeLocalRuleSensitiveFields(json: JSONObject): String {
    val type = if (json.has("type")) json.optInt("type", -1).toString() else "null"
    val subType = if (json.has("subType")) json.optInt("subType", -1).toString() else "null"
    val category = json.optString("category_name", "")
    val asset = json.optString("asset_name", "")
    val toAsset = json.optString("to_asset_name", "")
    return "type=$type,subType=$subType,category=$category,asset=$asset,toAsset=$toAsset"
}

internal fun findBestMatch(input: String, candidates: List<String>): String? {
    if (input.isBlank()) return null
    val rawInput = input.trim()
    if (candidates.contains(rawInput)) return rawInput

    val normalizedInput = normalizeCategoryPath(rawInput)
    candidates.firstOrNull { normalizeCategoryPath(it) == normalizedInput }?.let { return it }

    val leafToken = categoryLeafToken(normalizedInput)
    candidates.firstOrNull { categoryLeafToken(it) == leafToken }?.let { return it }

    val compactInput = categoryCompactToken(normalizedInput)
    candidates.firstOrNull { categoryCompactToken(it) == compactInput }?.let { return it }
    return null
}

internal fun resolveOtherCategory(candidates: List<String>): String? =
    candidates.find { it.contains("其他") || it.contains("其它") }

internal fun normalizeBillType(rawType: Int): Int = when (rawType) {
    0, 1, 2 -> rawType
    3 -> 2
    else -> 0
}

private fun normalizeMisplacedAssetOnExpenseOrIncome(root: JSONObject, assetNames: List<String>) {
    if (assetNames.isEmpty()) return

    fun isKnownAsset(name: String): Boolean =
        assetNames.any { it.equals(name, ignoreCase = true) }

    fun normalize(json: JSONObject) {
        val type = normalizeBillType(json.optInt("type", 0))
        if (type != DbBill.TYPE_EXPENSE && type != DbBill.TYPE_INCOME) return
        val fromAsset = json.optString("asset_name", "").trim()
        val toAsset = json.optString("to_asset_name", "").trim()
        if (fromAsset.isNotBlank() || toAsset.isBlank()) return
        if (!isKnownAsset(toAsset)) return
        json.put("asset_name", toAsset)
        json.put("to_asset_name", "")
    }

    if (root.has("bills")) {
        val bills = root.getJSONArray("bills")
        for (i in 0 until bills.length()) {
            normalize(bills.getJSONObject(i))
        }
    } else if (root.has("amount")) {
        normalize(root)
    }
}

private fun enforceTransferRequiresValidAssets(
    root: JSONObject,
    assetNames: List<String>,
    expenseCats: List<String>
) {
    if (assetNames.isEmpty()) return
    val fallbackExpenseCategory = resolveOtherCategory(expenseCats) ?: "其他"

    fun isKnownAsset(name: String): Boolean =
        assetNames.any { it.equals(name, ignoreCase = true) }

    fun normalize(json: JSONObject) {
        val type = normalizeBillType(json.optInt("type", 0))
        if (type != DbBill.TYPE_TRANSFER) return

        val isRepayment =
            json.optInt("subType", 0) == DbBill.SUBTYPE_REPAYMENT ||
                json.optString("category_name", "").trim() == "还款"
        if (isRepayment) return

        val fromAsset = json.optString("asset_name", "").trim()
        val toAsset = json.optString("to_asset_name", "").trim()
        val validTransfer = fromAsset.isNotBlank() && toAsset.isNotBlank() && isKnownAsset(fromAsset) && isKnownAsset(toAsset)
        if (validTransfer) return

        json.put("type", DbBill.TYPE_EXPENSE)
        if (json.has("subType")) json.remove("subType")
        json.put("to_asset_name", "")
        val category = json.optString("category_name", "").trim()
        if (category.isBlank() || category == "转账") {
            json.put("category_name", fallbackExpenseCategory)
        }
    }

    if (root.has("bills")) {
        val bills = root.getJSONArray("bills")
        for (i in 0 until bills.length()) {
            normalize(bills.getJSONObject(i))
        }
    } else if (root.has("amount")) {
        normalize(root)
    }
}

private fun enforceNoAssetMode(root: JSONObject) {
    fun normalizeBill(json: JSONObject) {
        val normalizedType = if (json.optInt("type", 0) == DbBill.TYPE_INCOME) DbBill.TYPE_INCOME else DbBill.TYPE_EXPENSE
        json.put("type", normalizedType)
        json.put("asset_name", "")
        json.put("to_asset_name", "")
        json.put("fee", 0.0)
        if (json.has("subType")) json.remove("subType")
    }

    if (root.has("bills")) {
        val bills = root.getJSONArray("bills")
        for (i in 0 until bills.length()) {
            normalizeBill(bills.getJSONObject(i))
        }
    } else if (root.has("amount")) {
        normalizeBill(root)
    }
}

private val CATEGORY_SEPARATOR_REGEX = Regex("\\s*(/:::/|/::/|::|/|\\\\|\\||>|->|=>|→|:|·)\\s*")

internal fun normalizeCategoryPath(input: String): String {
    if (input.isBlank()) return ""
    return input
        .trim()
        .replace('／', '/')
        .replace('＞', '>')
        .replace('—', '-')
        .replace('–', '-')
        .replace(CATEGORY_SEPARATOR_REGEX, "/::/")
        .replace(Regex("(/::/)+"), "/::/")
        .trim { it == '/' || it.isWhitespace() }
}

internal fun categoryLeafToken(value: String): String =
    categoryToken(normalizeCategoryPath(value).substringAfterLast("/::/"))

internal fun categoryCompactToken(value: String): String =
    categoryToken(normalizeCategoryPath(value)).replace("/::/", "")

internal fun categoryToken(value: String): String =
    value.lowercase(Locale.ROOT).replace(" ", "")
