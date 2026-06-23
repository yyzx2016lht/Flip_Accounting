package com.taostudio.tapaccounting.chat.query

import com.taostudio.tapaccounting.chat.ai.AiTimeRangeParser
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * 查询草稿管理器 —— 管理当前活跃的查询草稿，处理多轮修正和查询执行。
 *
 * 生命周期与 ChatActivity 一致。草稿仅存内存，不持久化。
 */
class QueryDraftManager(
    private val db: AppDatabase,
    private val getCurrentBookName: () -> String
) {
    /** 当前活跃的查询草稿 */
    var currentDraft: QueryDraft? = null
        private set

    /** 最近一次查询结果 */
    var lastResult: QueryResult? = null
        private set

    private val billSource = RoomQueryBillSource(db)
    private val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

    /**
     * 尝试将用户输入解析为查询草稿。
     * 如果解析成功，设置 currentDraft 并返回；否则返回 null。
     */
    suspend fun parseAndCreateDraft(userText: String, context: QueryContext): QueryDraft? {
        val draft = QueryDraftLocalParser.parse(userText, context) ?: return null
        currentDraft = draft
        lastResult = null
        return draft
    }

    /**
     * 尝试将用户输入识别为对当前草稿的修正。
     * 如果是修正，更新 currentDraft 并返回 true；否则返回 false。
     */
    suspend fun applyCorrection(userText: String, context: QueryContext): Boolean {
        val draft = currentDraft ?: return false
        val correction = QueryDraftLocalParser.detectCorrection(userText) ?: return false

        when (correction) {
            is QueryDraftCorrection.UpdateKeyword -> {
                currentDraft = draft.copy(
                    keyword = correction.newKeyword,
                    updatedAt = System.currentTimeMillis()
                )
            }
            is QueryDraftCorrection.UpdateTime -> {
                val zoneId = ZoneId.of(context.timezoneId)
                val today = Instant.ofEpochMilli(context.nowMillis).atZone(zoneId).toLocalDate()
                val timeRange = AiTimeRangeParser.parse(correction.phrase, today, zoneId)?.let {
                    QueryTimeRange(
                        startMillis = it.startMillis,
                        endMillis = it.endMillis,
                        rangeKey = normalizeRangeKey(it.phrase),
                        label = it.phrase
                    )
                }
                if (timeRange != null) {
                    currentDraft = draft.copy(
                        timeRange = timeRange,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
            is QueryDraftCorrection.UpdateBookScope -> {
                currentDraft = draft.copy(
                    bookScope = correction.scope,
                    bookName = if (correction.scope == BookScope.CURRENT) context.currentBookName else null,
                    updatedAt = System.currentTimeMillis()
                )
            }
            is QueryDraftCorrection.UpdateBillType -> {
                currentDraft = draft.copy(
                    billType = correction.billType,
                    updatedAt = System.currentTimeMillis()
                )
            }
            is QueryDraftCorrection.ExecuteStats, is QueryDraftCorrection.ExecuteSearch -> {
                // 执行指令不在这里处理，由调用方判断
                return false
            }
        }
        return true
    }

    /** 检测是否是执行指令 */
    fun detectExecutionCommand(text: String): QueryDraftCorrection? {
        val correction = QueryDraftLocalParser.detectCorrection(text)
        return when (correction) {
            is QueryDraftCorrection.ExecuteStats,
            is QueryDraftCorrection.ExecuteSearch -> correction
            else -> null
        }
    }

    /**
     * 执行统计查询。
     * 返回结构化的 QueryResult。
     */
    suspend fun executeStats(context: QueryContext): QueryResult? {
        val draft = currentDraft ?: return null
        val bills = loadAndFilterBills(draft, context)

        val expenseBills = bills.filter {
            (it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND) ||
                it.subType == Bill.SUBTYPE_REFUND
        }
        val totalAmount = expenseBills.sumOf { it.amount }
        val billCount = expenseBills.size

        val preview = expenseBills.take(3).map { bill ->
            BillPreview(
                id = bill.id,
                time = bill.time,
                type = bill.type,
                amount = bill.amount,
                remark = bill.remark,
                categoryName = bill.categoryName,
                accountName = bill.accountName,
                currency = bill.currency
            )
        }

        val topCategories = expenseBills
            .groupBy { it.categoryName.ifBlank { "未分类" } }
            .mapValues { (_, rows) -> CategoryAmount(
                categoryName = rows.first().categoryName.ifBlank { "未分类" },
                amount = rows.sumOf { it.amount },
                count = rows.size
            ) }
            .values
            .sortedByDescending { it.amount }
            .take(5)

        val result = QueryResult(
            draft = draft,
            totalAmount = totalAmount,
            billCount = billCount,
            billsPreview = preview,
            topCategories = topCategories
        )
        lastResult = result
        return result
    }

    /**
     * 执行搜索查询。
     * 返回匹配的账单列表。
     */
    suspend fun executeSearch(context: QueryContext): List<Bill>? {
        val draft = currentDraft ?: return null
        return loadAndFilterBills(draft, context)
    }

    /** 清除当前草稿 */
    fun clearDraft() {
        currentDraft = null
        lastResult = null
    }

    /** 是否有活跃的草稿 */
    fun hasActiveDraft(): Boolean = currentDraft != null

    private suspend fun loadAndFilterBills(draft: QueryDraft, context: QueryContext): List<Bill> {
        val bookScope = resolveBooks(draft, context)
        val source = when (draft.queryType) {
            QueryType.LATEST_BILL, QueryType.RECENT_BILLS -> {
                billSource.loadRecent(limit = 50, books = bookScope)
            }
            else -> {
                val range = draft.timeRange
                if (range == null || range.startMillis == null || range.endMillis == null) return emptyList()
                billSource.loadBetween(range.startMillis, range.endMillis, books = bookScope)
            }
        }
        return source
            .filter { matchesBillType(it, draft.billType) }
            .filter { matchesAsset(it, draft.assetId, draft.assetName) }
            .filter { matchesCategory(it, draft.categoryId, draft.categoryName) }
            .filter { matchesKeyword(it, draft.keyword) }
            .sortedByDescending { it.time }
            .let { bills ->
                when (draft.queryType) {
                    QueryType.LATEST_BILL -> bills.take(1)
                    QueryType.RECENT_BILLS -> bills.take(draft.recentCount)
                    else -> bills
                }
            }
    }

    private fun resolveBooks(draft: QueryDraft, context: QueryContext): List<String>? {
        if (draft.bookScope == BookScope.ALL) return null
        val target = draft.bookName ?: context.currentBookName
        return listOf(target)
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
        if (accountName.isNullOrBlank()) return assetId == null
        return bill.accountName.contains(accountName, ignoreCase = true) ||
            bill.toAccountName.contains(accountName, ignoreCase = true)
    }

    private fun matchesCategory(bill: Bill, categoryId: Long?, categoryName: String?): Boolean {
        if (categoryId != null && bill.categoryId == categoryId) return true
        if (categoryName.isNullOrBlank()) return categoryId == null
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

    private fun normalizeRangeKey(phrase: String): String? = when (phrase) {
        "今天", "今日" -> "TODAY"
        "昨天" -> "YESTERDAY"
        "本周", "这周", "这个星期" -> "THIS_WEEK"
        "上周", "上星期" -> "LAST_WEEK"
        "本月", "这个月", "当月" -> "THIS_MONTH"
        "上个月", "上月" -> "LAST_MONTH"
        "今年", "本年" -> "THIS_YEAR"
        else -> null
    }

    /** 格式化查询结果为可读文本 */
    fun formatResultText(result: QueryResult): String {
        val draft = result.draft
        val timeLabel = draft.timeRange?.label ?: "全部时间"
        val billTypeLabel = formatBillType(draft.billType)

        return if (result.billCount == 0) {
            "${timeLabel}没有查到匹配的${billTypeLabel}账单。"
        } else {
            "${timeLabel}${billTypeLabel}共 ${result.billCount} 笔，合计 ${String.format(Locale.getDefault(), "%.2f", result.totalAmount)}"
        }
    }

    /** 格式化查询条件摘要 */
    fun formatConditionsText(draft: QueryDraft): String {
        val parts = mutableListOf<String>()
        draft.keyword?.let { parts.add("关键词：$it") }
        draft.categoryName?.let { parts.add("分类：$it") }
        draft.assetName?.let { parts.add("资产：$it") }
        draft.timeRange?.let { parts.add("时间：${it.label ?: "未指定"}") }
        parts.add("类型：${formatBillType(draft.billType)}")
        parts.add("账本：${formatBookScope(draft.bookScope)}")
        return parts.joinToString("\n")
    }

    private fun formatBillType(billType: QueryBillType): String = when (billType) {
        QueryBillType.EXPENSE -> "支出"
        QueryBillType.INCOME -> "收入"
        QueryBillType.TRANSFER -> "转账"
        QueryBillType.REPAYMENT -> "还款"
        QueryBillType.REFUND -> "退款"
        QueryBillType.ANY -> "全部"
    }

    private fun formatBookScope(scope: BookScope): String = when (scope) {
        BookScope.CURRENT -> "当前账本"
        BookScope.ALL -> "全部账本"
        BookScope.SPECIFIC -> "指定账本"
    }
}
