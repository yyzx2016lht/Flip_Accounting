package com.taostudio.tapaccounting.chat.query

import java.util.UUID

data class QueryTimeRange(
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    val rangeKey: String? = null,
    val label: String? = null
) {
    fun isValid(): Boolean = startMillis != null && endMillis != null && startMillis <= endMillis
}

enum class QueryIntent {
    QUERY_BILLS,
    QUERY_ASSET_STATS,
    QUERY_CATEGORY_STATS,
    QUERY_EXISTENCE,
    OPEN_STATS_PAGE,
    OPEN_ASSET_STATS_PAGE,
    CLARIFY,
    UNSUPPORTED
}

enum class QueryAggregation {
    TOTAL,
    COUNT,
    BY_CATEGORY,
    BY_DAY,
    BY_ASSET,
    EXISTENCE,
    LIST,
    LATEST
}

enum class QueryBillType {
    EXPENSE,
    INCOME,
    TRANSFER,
    REPAYMENT,
    REFUND,
    ANY
}

/** 查询草稿的查询类型 */
enum class QueryType {
    AMOUNT_TOTAL,
    BILL_LIST,
    LATEST_BILL,
    RECENT_BILLS,
    EXISTS_KEYWORD,
    TOP_CATEGORIES,
    PERIOD_COMPARE,
    BOOK_SUMMARY,
    ASSET_SUMMARY
}

/** 账本范围 */
enum class BookScope {
    CURRENT,
    ALL,
    SPECIFIC
}

/** 查询草稿 —— 用户可见、可编辑、可确认的查询条件 */
data class QueryDraft(
    val id: String = UUID.randomUUID().toString(),
    val queryType: QueryType,
    val keyword: String? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val assetId: Long? = null,
    val assetName: String? = null,
    val bookScope: BookScope = BookScope.CURRENT,
    val bookName: String? = null,
    val billType: QueryBillType = QueryBillType.EXPENSE,
    val timeRange: QueryTimeRange? = null,
    val aggregation: QueryAggregation = QueryAggregation.TOTAL,
    val recentCount: Int = 1,
    val sourceText: String,
    val confidence: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 账单预览条目 */
data class BillPreview(
    val id: Long,
    val time: Long,
    val type: Int,
    val amount: Double,
    val remark: String,
    val categoryName: String,
    val accountName: String,
    val currency: String = "CNY"
)

/** 查询结果 —— 本地执行查询后的结构化结果 */
data class QueryResult(
    val draft: QueryDraft,
    val totalAmount: Double? = null,
    val billCount: Int = 0,
    val billsPreview: List<BillPreview> = emptyList(),
    val topCategories: List<CategoryAmount> = emptyList(),
    val generatedAt: Long = System.currentTimeMillis()
)

data class CategoryAmount(
    val categoryName: String,
    val amount: Double,
    val count: Int
)

data class QuerySlots(
    val timeRange: QueryTimeRange? = null,
    val accountName: String? = null,
    val assetId: Long? = null,
    val categoryName: String? = null,
    val categoryId: Long? = null,
    val keyword: String? = null,
    val billType: QueryBillType = QueryBillType.ANY,
    val aggregation: QueryAggregation = QueryAggregation.TOTAL,
    val bookName: String? = null,
    val currency: String? = null,
    val shouldNavigate: Boolean = false,
    val confidence: Double = 0.0,
    val clarifyQuestion: String? = null
)

data class QueryAction(
    val intent: QueryIntent,
    val slots: QuerySlots = QuerySlots()
)

data class QueryExecutionResult(
    val reply: String,
    val navigated: Boolean = false,
    val needsClarification: Boolean = false,
    val navigateIntent: QueryIntent? = null,
    val navigateSlots: QuerySlots? = null
)

data class QueryAssetOption(
    val id: Long,
    val name: String,
    val currency: String
)

data class QueryCategoryOption(
    val id: Long,
    val name: String,
    val type: Int
)

data class QueryCapabilities(
    val canOpenStatsPage: Boolean,
    val canOpenAssetStatsPage: Boolean,
    val supportsStatsExternalFilter: Boolean,
    val supportsAssetStatsTimeRange: Boolean,
    val supportsAssetStatsBillType: Boolean
)

data class QueryContext(
    val nowMillis: Long,
    val timezoneId: String,
    val currentBookName: String,
    val availableBooks: List<String>,
    val assets: List<QueryAssetOption>,
    val categories: List<QueryCategoryOption>,
    val currencies: List<String>,
    val capabilities: QueryCapabilities,
    val recentBillHints: List<String>
)
