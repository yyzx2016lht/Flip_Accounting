package tao.test.flipaccounting.chat.ai

enum class AiIntentType {
    GENERAL_CHAT,
    BOOKKEEPING_CREATE,
    BOOKKEEPING_QUERY,
    BOOKKEEPING_UPDATE,
    BOOKKEEPING_DELETE,
    SESSION_QUERY,
    SESSION_UPDATE,
    MEDIA_ANALYZE,
    UNKNOWN,

    @Deprecated("Use BOOKKEEPING_CREATE")
    BOOKKEEPING,
    @Deprecated("Use BOOKKEEPING_UPDATE")
    MODIFY_BILL,
    @Deprecated("Use BOOKKEEPING_QUERY")
    QUERY
}

enum class AiBookkeepingMode {
    SINGLE,
    MULTI,
    UNSPECIFIED
}

data class AiTimeRange(
    val phrase: String,
    val startMillis: Long,
    val endMillis: Long
)

data class AiIntentSlots(
    val timeRange: AiTimeRange? = null,
    val account: String? = null,
    val category: String? = null,
    val amount: Double? = null,
    val keyword: String? = null
)

data class AiRouteResult(
    val intentType: AiIntentType,
    val confidence: Double,
    val slots: AiIntentSlots = AiIntentSlots(),
    val bookkeepingMode: AiBookkeepingMode = AiBookkeepingMode.UNSPECIFIED
) {
    fun missingQuerySlots(): List<String> {
        if (intentType != AiIntentType.QUERY && intentType != AiIntentType.BOOKKEEPING_QUERY) return emptyList()
        val missing = mutableListOf<String>()
        if (slots.timeRange == null) missing += "时间范围"
        return missing
    }
}
