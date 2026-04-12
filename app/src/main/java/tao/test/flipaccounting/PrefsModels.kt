package tao.test.flipaccounting

/**
 * Shared lightweight models used by preferences and legacy serialization.
 */
data class CategoryNode(
    val name: String,
    val icon: String,
    val subs: MutableList<CategoryNode> = mutableListOf()
) {
    /** Real Room id, filled after category tree is built. */
    var id: Long = 0L
}

data class Asset(
    val name: String,
    val type: String,
    val currency: String,
    val icon: String = ""
)

data class Bill(
    val amount: Double,
    val type: Int,
    val assetName: String,
    val categoryName: String,
    val time: String,
    val remarks: String = "",
    val iconUrl: String = "",
    val recordTime: String = ""
)

data class OcrDebugRecord(
    val timestamp: Long,
    val source: String,
    val text: String
)

