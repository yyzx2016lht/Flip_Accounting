package tao.test.flipaccounting.chat.ai

enum class AiIntentType {
    BOOKKEEPING,
    QUERY,
    GENERAL_CHAT,
    UNKNOWN
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
        if (intentType != AiIntentType.QUERY) return emptyList()
        val missing = mutableListOf<String>()
        if (slots.timeRange == null) missing += "时间范围"
        return missing
    }
}
