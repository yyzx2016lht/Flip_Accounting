package tao.test.flipaccounting

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.data.local.entity.Bill as DbBill
import tao.test.flipaccounting.data.repository.CategoryRepository
import tao.test.flipaccounting.logic.CurrencyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class AIAccountingPromptContext(
    val dbAssets: List<Asset>,
    val assetInfoList: List<Map<String, String>>,
    val assetNames: List<String>,
    val expenseCats: List<String>,
    val incomeCats: List<String>,
    val currencies: List<String>,
    val currentTimeStr: String,
    val assetFeatureEnabled: Boolean,
    val availableBooks: List<String>,
    val demoAsset: String,
    val demoExpenseCat: String,
    val demoIncomeCat: String,
    val expenseLeafCats: List<String>,
    val incomeLeafCats: List<String>
)

internal suspend fun buildAccountingPromptContext(ctx: Context): AIAccountingPromptContext {
    val assetFeatureEnabled = Prefs.isAssetFeatureEnabled(ctx)
    val db = AppDatabase.getDatabase(ctx)
    val dbAssets = withContext(Dispatchers.IO) {
        db.assetDao().getAllAssetsList()
    }
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
            .filter { it.isNotBlank() && it != BookAccountManager.ALL_BOOK }
            .distinct()
    }

    return AIAccountingPromptContext(
        dbAssets = dbAssets,
        assetInfoList = assetInfoList,
        assetNames = assetNames,
        expenseCats = expenseCats,
        incomeCats = incomeCats,
        currencies = CurrencyManager.getEnabledCurrencies(ctx),
        currentTimeStr = currentTimeStr,
        assetFeatureEnabled = assetFeatureEnabled,
        availableBooks = availableBooks,
        demoAsset = assetNames.firstOrNull() ?: "微信",
        demoExpenseCat = expenseCats.firstOrNull() ?: "其他",
        demoIncomeCat = incomeCats.firstOrNull() ?: "工资",
        expenseLeafCats = expenseCats.map { it.substringAfterLast("/::/") }.distinct(),
        incomeLeafCats = incomeCats.map { it.substringAfterLast("/::/") }.distinct()
    )
}

private fun buildCategoryOptions(tree: List<CategoryNode>): List<String> = buildList {
    tree.forEach { parentNode ->
        add(parentNode.name)
        parentNode.subs.forEach { childNode ->
            add("${parentNode.name}/::/${childNode.name}")
        }
    }
}

internal fun normalizeAccountingResult(
    root: JSONObject,
    expenseCats: List<String>,
    incomeCats: List<String>,
    assetNames: List<String>,
    assetFeatureEnabled: Boolean
) {
    fun normalizeBillJson(bill: JSONObject) {
        val rawType = bill.optInt("type", 0)
        val type = normalizeBillType(rawType)
        bill.put("type", type)
        if (type == DbBill.TYPE_TRANSFER) {
            if (rawType == 3 || bill.optInt("subType", 0) == DbBill.SUBTYPE_REPAYMENT || bill.optString("category_name") == "还款") {
                bill.put("subType", DbBill.SUBTYPE_REPAYMENT)
                bill.put("category_name", "还款")
            } else if (bill.optString("category_name").isBlank()) {
                bill.put("category_name", "转账")
            }
            return
        }

        val candidates = if (type == DbBill.TYPE_INCOME) incomeCats else expenseCats
        val rawCategory = bill.optString("category_name", "")
        val normalizedCategory = rawCategory
            .replace(" > ", "/::/")
            .replace(" - ", "/::/")
            .replace(" / ", "/::/")
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
            normalizeBillJson(bills.getJSONObject(i))
        }
    } else if (root.has("amount")) {
        normalizeBillJson(root)
    }

    if (assetFeatureEnabled) {
        normalizeMisplacedAssetOnExpenseOrIncome(root, assetNames)
        enforceTransferRequiresValidAssets(root, assetNames, expenseCats)
    } else {
        enforceNoAssetMode(root)
    }
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

private val CATEGORY_SEPARATOR_REGEX = Regex("\\s*(/::/|::|/|>|-|->|=>|→|·)\\s*")

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
        .trim { it == '/' || it == ':' || it.isWhitespace() }
}

internal fun categoryLeafToken(value: String): String =
    categoryToken(normalizeCategoryPath(value).substringAfterLast("/::/"))

internal fun categoryCompactToken(value: String): String =
    categoryToken(normalizeCategoryPath(value)).replace("/::/", "")

internal fun categoryToken(value: String): String =
    value.lowercase(Locale.ROOT).replace(" ", "")
