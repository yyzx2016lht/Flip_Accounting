package com.taostudio.tapaccounting.chat.query

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.data.local.entity.Bill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface QueryBillSource {
    suspend fun loadRecent(limit: Int, books: List<String>?): List<Bill>
    suspend fun loadBetween(startMillis: Long, endMillis: Long, books: List<String>?): List<Bill>
}

class QueryExecutor(
    private val billSource: QueryBillSource
) {
    private val mdDate = SimpleDateFormat("MM-dd", Locale.getDefault())

    suspend fun execute(action: QueryAction, context: QueryContext): QueryExecutionResult {
        if (action.intent == QueryIntent.UNSUPPORTED) {
            return QueryExecutionResult("这个请求涉及写操作或高风险行为，我只能帮你做查询和跳转。")
        }
        if (action.intent == QueryIntent.CLARIFY) {
            return QueryExecutionResult(
                action.slots.clarifyQuestion ?: "我还不确定你的查询目标，能再具体一点吗？",
                needsClarification = true
            )
        }

        if (action.intent == QueryIntent.OPEN_STATS_PAGE) {
            return QueryExecutionResult(
                reply = "已为你准备好统计筛选。",
                navigated = false,
                navigateIntent = QueryIntent.OPEN_STATS_PAGE,
                navigateSlots = action.slots
            )
        }
        if (action.intent == QueryIntent.OPEN_ASSET_STATS_PAGE) {
            return QueryExecutionResult(
                reply = "已为你准备好资产统计筛选。",
                navigated = false,
                navigateIntent = QueryIntent.OPEN_ASSET_STATS_PAGE,
                navigateSlots = action.slots
            )
        }

        val bills = loadAndFilterBills(action, context)
        val reply = when (action.intent) {
            QueryIntent.QUERY_EXISTENCE -> renderExistenceReply(action, bills)
            QueryIntent.QUERY_ASSET_STATS -> renderAssetStatsReply(action, bills)
            QueryIntent.QUERY_CATEGORY_STATS -> renderCategoryStatsReply(action, bills)
            QueryIntent.QUERY_BILLS -> renderBillsReply(action, bills)
            else -> "我先按查询模式处理了，但这次没有匹配到有效动作。"
        }
        val navigateIntent = if (action.slots.shouldNavigate) {
            when (action.intent) {
                QueryIntent.QUERY_ASSET_STATS -> QueryIntent.OPEN_ASSET_STATS_PAGE
                QueryIntent.QUERY_CATEGORY_STATS, QueryIntent.QUERY_BILLS -> QueryIntent.OPEN_STATS_PAGE
                else -> null
            }
        } else {
            null
        }
        return QueryExecutionResult(
            reply = reply,
            navigated = false,
            navigateIntent = navigateIntent,
            navigateSlots = if (navigateIntent != null) action.slots else null
        )
    }

    private suspend fun loadAndFilterBills(action: QueryAction, context: QueryContext): List<Bill> {
        val slots = action.slots
        val bookScope = resolveBooks(slots.bookName, context)
        val source = when (slots.aggregation) {
            QueryAggregation.LATEST -> {
                billSource.loadRecent(limit = 50, books = bookScope)
            }
            else -> {
                val range = slots.timeRange
                if (range == null || range.startMillis == null || range.endMillis == null) return emptyList()
                billSource.loadBetween(range.startMillis, range.endMillis, books = bookScope)
            }
        }
        return source
            .filter { matchesBillType(it, slots.billType) }
            .filter { matchesAsset(it, slots.assetId, slots.accountName) }
            .filter { matchesCategory(it, slots.categoryId, slots.categoryName) }
            .filter { matchesKeyword(it, slots.keyword) }
            .sortedByDescending { it.time }
    }

    private fun resolveBooks(bookName: String?, context: QueryContext): List<String>? {
        val target = BookAccountManager.normalizeBookName(bookName ?: context.currentBookName)
        if (target == BookAccountManager.ALL_BOOK) return null
        return listOf(BookAccountManager.resolveWritableBook(target))
    }

    private fun matchesBillType(bill: Bill, billType: QueryBillType): Boolean {
        return when (billType) {
            QueryBillType.EXPENSE -> bill.type == Bill.TYPE_EXPENSE && bill.subType != Bill.SUBTYPE_REFUND
            QueryBillType.INCOME -> bill.type == Bill.TYPE_INCOME
            QueryBillType.TRANSFER -> bill.type == Bill.TYPE_TRANSFER && bill.subType != Bill.SUBTYPE_REPAYMENT
            QueryBillType.REPAYMENT -> bill.type == Bill.TYPE_TRANSFER && bill.subType == Bill.SUBTYPE_REPAYMENT
            QueryBillType.REFUND -> bill.subType == Bill.SUBTYPE_REFUND
            QueryBillType.ANY -> true
        }
    }

    private fun matchesAsset(bill: Bill, assetId: Long?, accountName: String?): Boolean {
        if (assetId != null) {
            if (bill.accountId == assetId || bill.toAccountId == assetId) return true
        }
        if (accountName.isNullOrBlank()) return assetId == null || bill.accountId == assetId || bill.toAccountId == assetId
        return bill.accountName.contains(accountName, ignoreCase = true) ||
            bill.toAccountName.contains(accountName, ignoreCase = true)
    }

    private fun matchesCategory(bill: Bill, categoryId: Long?, categoryName: String?): Boolean {
        if (categoryId != null && bill.categoryId == categoryId) return true
        if (categoryName.isNullOrBlank()) return categoryId == null || bill.categoryId == categoryId
        return bill.categoryName.contains(categoryName, ignoreCase = true)
    }

    private fun matchesKeyword(bill: Bill, keyword: String?): Boolean {
        if (keyword.isNullOrBlank()) return true
        val normalized = keyword.trim().lowercase(Locale.getDefault())
        if (normalized.isBlank()) return true
        return listOf(
            bill.remark,
            bill.categoryName,
            bill.accountName,
            bill.toAccountName,
            bill.bookName,
            bill.currency
        ).any { value -> value.lowercase(Locale.getDefault()).contains(normalized) }
    }

    private fun renderExistenceReply(action: QueryAction, bills: List<Bill>): String {
        val keyword = action.slots.keyword ?: action.slots.categoryName ?: "该关键词"
        val rangeLabel = action.slots.timeRange?.label?.ifBlank { "该时间范围" } ?: "该时间范围"
        if (bills.isEmpty()) {
            return "我查了${rangeLabel}账单，没有找到分类、备注或账户中明显包含“$keyword”的记录。"
        }
        val expenseBills = bills.filter { it.type == Bill.TYPE_EXPENSE || it.subType == Bill.SUBTYPE_REFUND }
        val total = expenseBills.sumOf { it.amount }
        val top = expenseBills.take(3).joinToString("、") { bill ->
            "${mdDate.format(Date(bill.time))} ${bill.remark.ifBlank { bill.accountName.ifBlank { bill.categoryName.ifBlank { "未备注" } } }} ${formatAmount(bill.amount)}"
        }
        return "有。${rangeLabel}找到 ${expenseBills.size} 笔可能和“$keyword”相关的支出，共 ${formatAmount(total)}。主要是：$top。"
    }

    private fun renderAssetStatsReply(action: QueryAction, bills: List<Bill>): String {
        val account = action.slots.accountName ?: "该资产"
        val rangeLabel = action.slots.timeRange?.label ?: "该时间范围"
        if (bills.isEmpty()) {
            return "${rangeLabel}没有查到 $account 的匹配账单。"
        }
        val expenseBills = bills.filter { it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND }
        val total = expenseBills.sumOf { it.amount }
        val byCategory = expenseBills.groupBy { it.categoryName.ifBlank { "未分类" } }
            .mapValues { (_, rows) -> rows.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
            .take(3)
            .joinToString("、") { "${it.first} ${formatAmount(it.second)}" }
        return "${rangeLabel}${account}支出共 ${formatAmount(total)}，共 ${expenseBills.size} 笔。${if (byCategory.isNotBlank()) "最多的是 $byCategory。" else ""}"
    }

    private fun renderCategoryStatsReply(action: QueryAction, bills: List<Bill>): String {
        val rangeLabel = action.slots.timeRange?.label ?: "该时间范围"
        if (bills.isEmpty()) {
            val category = action.slots.categoryName ?: "目标分类"
            return "${rangeLabel}没有查到和“$category”相关的账单。"
        }
        val byCategory = bills.groupBy { it.categoryName.ifBlank { "未分类" } }
            .mapValues { (_, rows) -> rows.sumOf { row -> row.amount } }
            .toList()
            .sortedByDescending { it.second }
        val total = byCategory.sumOf { it.second }
        val top = byCategory.take(3).joinToString("、") { "${it.first} ${formatAmount(it.second)}" }
        return "${rangeLabel}共命中 ${bills.size} 笔，合计 ${formatAmount(total)}。主要分类：$top。"
    }

    private fun renderBillsReply(action: QueryAction, bills: List<Bill>): String {
        if (action.slots.aggregation == QueryAggregation.LATEST) {
            val latest = bills.firstOrNull()
                ?: return "没有查到最近账单。"
            val typeText = when {
                latest.subType == Bill.SUBTYPE_REFUND -> "退款"
                latest.type == Bill.TYPE_INCOME -> "收入"
                latest.type == Bill.TYPE_TRANSFER && latest.subType == Bill.SUBTYPE_REPAYMENT -> "还款"
                latest.type == Bill.TYPE_TRANSFER -> "转账"
                else -> "支出"
            }
            val title = latest.remark.ifBlank { latest.categoryName.ifBlank { latest.accountName.ifBlank { "未备注" } } }
            return "最近一笔是：${mdDate.format(Date(latest.time))} $typeText ${formatAmount(latest.amount)}，$title。"
        }

        val rangeLabel = action.slots.timeRange?.label ?: "该时间范围"
        if (bills.isEmpty()) {
            return "${rangeLabel}没有查到匹配账单。"
        }
        val expense = bills.filter { it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND }.sumOf { it.amount }
        val income = bills.filter { it.type == Bill.TYPE_INCOME }.sumOf { it.amount }
        val transfer = bills.filter { it.type == Bill.TYPE_TRANSFER && it.subType != Bill.SUBTYPE_REPAYMENT }.sumOf { it.amount }
        val byCategory = bills.groupBy { it.categoryName.ifBlank { "未分类" } }
            .mapValues { (_, rows) -> rows.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
            .take(3)
            .joinToString("、") { "${it.first} ${formatAmount(it.second)}" }
        return buildString {
            append("${rangeLabel}共命中 ${bills.size} 笔。")
            append("支出 ${formatAmount(expense)}，收入 ${formatAmount(income)}")
            if (transfer > 0) append("，转账 ${formatAmount(transfer)}")
            if (byCategory.isNotBlank()) append("。主要分类：$byCategory。")
        }
    }

    private fun formatAmount(amount: Double): String {
        return String.format(Locale.getDefault(), "%.2f", amount)
    }
}

