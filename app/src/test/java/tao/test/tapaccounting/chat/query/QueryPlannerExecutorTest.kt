package tao.test.tapaccounting.chat.query

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tao.test.tapaccounting.BookAccountManager
import tao.test.tapaccounting.data.local.entity.Bill
import java.time.LocalDateTime
import java.time.ZoneId

class QueryPlannerExecutorTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val nowMillis = LocalDateTime.of(2026, 4, 29, 9, 0).atZone(zoneId).toInstant().toEpochMilli()

    private fun millisOf(y: Int, m: Int, d: Int, h: Int, min: Int): Long {
        return LocalDateTime.of(y, m, d, h, min).atZone(zoneId).toInstant().toEpochMilli()
    }

    private val baseBills = listOf(
        Bill(
            id = 1,
            type = Bill.TYPE_EXPENSE,
            amount = 18.9,
            categoryId = 11,
            accountId = 101,
            categoryName = "购物",
            accountName = "Visa",
            time = millisOf(2026, 4, 22, 10, 10),
            remark = "超市买水果",
            bookName = BookAccountManager.DEFAULT_BOOK
        ),
        Bill(
            id = 2,
            type = Bill.TYPE_EXPENSE,
            amount = 12.0,
            categoryId = 11,
            accountId = 101,
            categoryName = "购物",
            accountName = "Visa",
            time = millisOf(2026, 4, 24, 11, 0),
            remark = "水果店",
            bookName = BookAccountManager.DEFAULT_BOOK
        ),
        Bill(
            id = 3,
            type = Bill.TYPE_EXPENSE,
            amount = 8.9,
            categoryId = 11,
            accountId = 101,
            categoryName = "购物",
            accountName = "Visa",
            time = millisOf(2026, 4, 26, 19, 30),
            remark = "便利店水果",
            bookName = BookAccountManager.DEFAULT_BOOK
        ),
        Bill(
            id = 4,
            type = Bill.TYPE_EXPENSE,
            amount = 35.0,
            categoryId = 10,
            accountId = 102,
            categoryName = "餐饮",
            accountName = "支付宝",
            time = millisOf(2026, 4, 28, 12, 0),
            remark = "午饭",
            bookName = BookAccountManager.DEFAULT_BOOK
        ),
        Bill(
            id = 5,
            type = Bill.TYPE_EXPENSE,
            amount = 66.0,
            categoryId = 10,
            accountId = 101,
            categoryName = "餐饮",
            accountName = "Visa",
            time = millisOf(2026, 4, 29, 8, 10),
            remark = "早餐",
            bookName = BookAccountManager.DEFAULT_BOOK
        )
    )

    private fun baseContext(assets: List<QueryAssetOption>): QueryContext {
        return QueryContext(
            nowMillis = nowMillis,
            timezoneId = zoneId.id,
            currentBookName = BookAccountManager.DEFAULT_BOOK,
            availableBooks = listOf(BookAccountManager.DEFAULT_BOOK),
            assets = assets,
            categories = listOf(
                QueryCategoryOption(10, "餐饮", 0),
                QueryCategoryOption(11, "购物", 0),
                QueryCategoryOption(12, "交通", 0)
            ),
            currencies = listOf("CNY"),
            capabilities = QueryCapabilities(
                canOpenStatsPage = true,
                canOpenAssetStatsPage = true,
                supportsStatsExternalFilter = true,
                supportsAssetStatsTimeRange = true,
                supportsAssetStatsBillType = true
            ),
            recentBillHints = listOf("超市买水果", "水果店", "午饭")
        )
    }

    @Test
    fun planner_handles_last_week_visa_expense() = runBlocking {
        val planner = QueryPlanner()
        val context = baseContext(
            assets = listOf(
                QueryAssetOption(101, "Visa", "CNY"),
                QueryAssetOption(102, "支付宝", "CNY")
            )
        )
        val action = planner.plan("帮我查一下上周 visa 卡的支出情况", context)

        assertEquals(QueryIntent.QUERY_ASSET_STATS, action.intent)
        assertEquals(101L, action.slots.assetId)
        assertNotNull(action.slots.timeRange)
    }

    @Test
    fun planner_and_executor_handle_fruit_existence_query() = runBlocking {
        val planner = QueryPlanner()
        val context = baseContext(
            assets = listOf(
                QueryAssetOption(101, "Visa", "CNY"),
                QueryAssetOption(102, "支付宝", "CNY")
            )
        )
        val action = planner.plan("我上周有买水果吗", context)
        val executor = QueryExecutor(FakeBillSource(baseBills))
        val result = executor.execute(action, context)

        assertTrue(result.reply.contains("有。"))
        assertTrue(result.reply.contains("水果"))
    }

    @Test
    fun planner_handles_this_month_category_total() = runBlocking {
        val planner = QueryPlanner()
        val context = baseContext(
            assets = listOf(QueryAssetOption(101, "Visa", "CNY"))
        )
        val action = planner.plan("本月餐饮花了多少", context)

        assertTrue(action.intent == QueryIntent.QUERY_CATEGORY_STATS || action.intent == QueryIntent.QUERY_BILLS)
        assertTrue(action.slots.categoryName == "餐饮" || action.slots.keyword == "餐饮")
        assertNotNull(action.slots.timeRange)
    }

    @Test
    fun planner_handles_latest_bill_query() = runBlocking {
        val planner = QueryPlanner()
        val context = baseContext(
            assets = listOf(QueryAssetOption(101, "Visa", "CNY"))
        )
        val action = planner.plan("最近一笔是什么", context)
        val executor = QueryExecutor(FakeBillSource(baseBills))
        val result = executor.execute(action, context)

        assertEquals(QueryAggregation.LATEST, action.slots.aggregation)
        assertTrue(result.reply.contains("最近一笔"))
    }

    @Test
    fun planner_handles_open_last_week_stats() = runBlocking {
        val planner = QueryPlanner()
        val context = baseContext(
            assets = listOf(QueryAssetOption(101, "Visa", "CNY"))
        )
        val action = planner.plan("打开上周统计", context)

        assertEquals(QueryIntent.OPEN_STATS_PAGE, action.intent)
        assertTrue(action.slots.shouldNavigate)
        assertNotNull(action.slots.timeRange)
    }

    @Test
    fun planner_handles_alipay_yesterday_expense() = runBlocking {
        val planner = QueryPlanner()
        val context = baseContext(
            assets = listOf(
                QueryAssetOption(101, "Visa", "CNY"),
                QueryAssetOption(102, "支付宝", "CNY")
            )
        )
        val action = planner.plan("查支付宝昨天支出", context)

        assertEquals(102L, action.slots.assetId)
        assertNotNull(action.slots.timeRange)
        assertEquals(QueryBillType.EXPENSE, action.slots.billType)
    }

    @Test
    fun unknown_category_becomes_keyword_not_hardcoded_failure() = runBlocking {
        val planner = QueryPlanner()
        val context = baseContext(
            assets = listOf(QueryAssetOption(101, "Visa", "CNY"))
        )
        val action = planner.plan("本月水果花了多少", context)
        val result = QueryExecutor(FakeBillSource(baseBills)).execute(action, context)

        assertTrue(action.intent != QueryIntent.UNSUPPORTED)
        assertTrue(action.slots.keyword?.contains("水果") == true || action.intent == QueryIntent.CLARIFY)
        assertTrue(result.reply.contains("水果") || result.reply.contains("命中"))
    }

    @Test
    fun ambiguous_assets_should_clarify() = runBlocking {
        val planner = QueryPlanner()
        val context = baseContext(
            assets = listOf(
                QueryAssetOption(201, "Visa", "CNY"),
                QueryAssetOption(202, "Visa Credit", "CNY"),
                QueryAssetOption(203, "支付宝", "CNY")
            )
        )
        val action = planner.plan("查visa昨天支出", context)

        assertEquals(QueryIntent.CLARIFY, action.intent)
        assertTrue(action.slots.clarifyQuestion.orEmpty().contains("多个"))
    }

    @Test
    fun write_requests_are_blocked() = runBlocking {
        val planner = QueryPlanner()
        val context = baseContext(
            assets = listOf(QueryAssetOption(101, "Visa", "CNY"))
        )
        val deleteAction = planner.plan("删除上周账单", context)
        val modifyAction = planner.plan("把上周餐饮改成交通", context)

        assertEquals(QueryIntent.UNSUPPORTED, deleteAction.intent)
        assertEquals(QueryIntent.UNSUPPORTED, modifyAction.intent)
    }

    private class FakeBillSource(
        private val bills: List<Bill>
    ) : QueryBillSource {
        override suspend fun loadRecent(limit: Int, books: List<String>?): List<Bill> {
            val scoped = if (books == null) bills else bills.filter { books.contains(it.bookName) }
            return scoped.sortedByDescending { it.time }.take(limit)
        }

        override suspend fun loadBetween(startMillis: Long, endMillis: Long, books: List<String>?): List<Bill> {
            val scoped = if (books == null) bills else bills.filter { books.contains(it.bookName) }
            return scoped
                .filter { it.time in startMillis..endMillis }
                .sortedByDescending { it.time }
        }
    }
}
