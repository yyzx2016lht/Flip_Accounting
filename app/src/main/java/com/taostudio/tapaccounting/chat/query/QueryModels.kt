package com.taostudio.tapaccounting.chat.query

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

