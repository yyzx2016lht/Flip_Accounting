package tao.test.tapaccounting.ui.main.stats

data class StatsExternalQueryFilter(
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    val label: String? = null,
    val bookName: String? = null,
    val currency: String? = null
)

object StatsExternalQueryBridge {
    @Volatile
    private var pendingFilter: StatsExternalQueryFilter? = null

    fun publish(filter: StatsExternalQueryFilter) {
        pendingFilter = filter
    }

    fun consume(): StatsExternalQueryFilter? {
        val value = pendingFilter
        pendingFilter = null
        return value
    }
}
