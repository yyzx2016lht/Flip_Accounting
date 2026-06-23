package com.taostudio.tapaccounting.chat.query

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class QueryDraftLocalParserTest {

    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = LocalDate.of(2026, 6, 23)
    private val fixedNowMillis = fixedNow.atStartOfDay(zoneId).toInstant().toEpochMilli()

    private val sampleAssets = listOf(
        QueryAssetOption(1, "微信", "CNY"),
        QueryAssetOption(2, "支付宝", "CNY"),
        QueryAssetOption(3, "招商信用卡", "CNY"),
        QueryAssetOption(4, "Visa", "USD")
    )

    private val sampleCategories = listOf(
        QueryCategoryOption(1, "餐饮", 0),
        QueryCategoryOption(2, "交通", 0),
        QueryCategoryOption(3, "水果", 0),
        QueryCategoryOption(4, "购物", 0)
    )

    private val sampleBooks = listOf("日常账本", "旅行账本")

    private val context = QueryContext(
        nowMillis = fixedNowMillis,
        timezoneId = "Asia/Shanghai",
        currentBookName = "日常账本",
        availableBooks = sampleBooks,
        assets = sampleAssets,
        categories = sampleCategories,
        currencies = listOf("CNY", "USD"),
        capabilities = QueryCapabilities(
            canOpenStatsPage = true,
            canOpenAssetStatsPage = true,
            supportsStatsExternalFilter = true,
            supportsAssetStatsTimeRange = true,
            supportsAssetStatsBillType = true
        ),
        recentBillHints = listOf("苹果", "超市", "餐饮")
    )

    // === 时间解析测试 ===

    @Test
    fun `本月时间解析`() {
        val draft = QueryDraftLocalParser.parse("这个月花了多少钱", context)
        assertNotNull(draft)
        assertEquals("THIS_MONTH", draft!!.timeRange?.rangeKey)
        assertTrue(draft.timeRange!!.startMillis!! <= draft.timeRange.endMillis!!)
    }

    @Test
    fun `上个月时间解析`() {
        val draft = QueryDraftLocalParser.parse("上个月花了多少", context)
        assertNotNull(draft)
        assertEquals("LAST_MONTH", draft!!.timeRange?.rangeKey)
    }

    @Test
    fun `今天时间解析`() {
        val draft = QueryDraftLocalParser.parse("今天花了多少", context)
        assertNotNull(draft)
        assertEquals("TODAY", draft!!.timeRange?.rangeKey)
    }

    @Test
    fun `昨天时间解析`() {
        val draft = QueryDraftLocalParser.parse("昨天花了多少", context)
        assertNotNull(draft)
        assertEquals("YESTERDAY", draft!!.timeRange?.rangeKey)
    }

    @Test
    fun `本周时间解析`() {
        val draft = QueryDraftLocalParser.parse("本周花了多少", context)
        assertNotNull(draft)
        assertEquals("THIS_WEEK", draft!!.timeRange?.rangeKey)
    }

    @Test
    fun `上周时间解析`() {
        val draft = QueryDraftLocalParser.parse("上周花了多少", context)
        assertNotNull(draft)
        assertEquals("LAST_WEEK", draft!!.timeRange?.rangeKey)
    }

    // === 关键词提取测试 ===

    @Test
    fun `关键词提取 - 买苹果花了多少`() {
        val draft = QueryDraftLocalParser.parse("这个月买苹果花了多少钱", context)
        assertNotNull(draft)
        assertEquals("苹果", draft!!.keyword)
    }

    @Test
    fun `关键词提取 - 有没有买过水果`() {
        val draft = QueryDraftLocalParser.parse("有没有买过水果", context)
        assertNotNull(draft)
        // 关键词可能是"水果"或"买过水果"，取决于触发词匹配顺序
        assertTrue(draft!!.keyword?.contains("水果") == true || draft.keyword?.contains("买") == true)
        assertEquals(QueryType.EXISTS_KEYWORD, draft.queryType)
    }

    // === 分类匹配测试 ===

    @Test
    fun `分类名匹配 - 餐饮花了多少`() {
        val draft = QueryDraftLocalParser.parse("这个月餐饮花了多少", context)
        assertNotNull(draft)
        assertEquals("餐饮", draft!!.categoryName)
        assertEquals(1L, draft.categoryId)
    }

    // === 资产名匹配测试 ===

    @Test
    fun `资产名匹配 - 微信花了多少`() {
        val draft = QueryDraftLocalParser.parse("这个月微信花了多少", context)
        assertNotNull(draft)
        assertEquals("微信", draft!!.assetName)
        assertEquals(1L, draft.assetId)
    }

    // === 账本范围测试 ===

    @Test
    fun `全部账本识别`() {
        val draft = QueryDraftLocalParser.parse("全部账本这个月花了多少", context)
        assertNotNull(draft)
        assertEquals(BookScope.ALL, draft!!.bookScope)
    }

    // === 写操作拒绝测试 ===

    @Test
    fun `写操作拒绝 - 删除`() {
        val draft = QueryDraftLocalParser.parse("删除上一笔", context)
        assertNull(draft)
    }

    @Test
    fun `写操作拒绝 - 记账`() {
        val draft = QueryDraftLocalParser.parse("帮我记一笔", context)
        assertNull(draft)
    }

    @Test
    fun `写操作拒绝 - 修改`() {
        val draft = QueryDraftLocalParser.parse("把苹果改成水果", context)
        assertNull(draft)
    }

    // === 最近一笔测试 ===

    @Test
    fun `最近一笔识别`() {
        val draft = QueryDraftLocalParser.parse("最近一笔是什么", context)
        assertNotNull(draft)
        assertEquals(QueryType.LATEST_BILL, draft!!.queryType)
    }

    // === 最近 N 笔测试 ===

    @Test
    fun `最近N笔识别`() {
        val draft = QueryDraftLocalParser.parse("最近3笔账单", context)
        assertNotNull(draft)
        assertEquals(QueryType.RECENT_BILLS, draft!!.queryType)
        assertEquals(3, draft.recentCount)
    }

    // === 存在性查询测试 ===

    @Test
    fun `存在性查询识别`() {
        val draft = QueryDraftLocalParser.parse("有没有买过苹果", context)
        assertNotNull(draft)
        assertEquals(QueryType.EXISTS_KEYWORD, draft!!.queryType)
    }

    // === 账单类型检测测试 ===

    @Test
    fun `账单类型 - 收入`() {
        val draft = QueryDraftLocalParser.parse("这个月收入多少", context)
        assertNotNull(draft)
        assertEquals(QueryBillType.INCOME, draft!!.billType)
    }

    @Test
    fun `账单类型 - 转账`() {
        val draft = QueryDraftLocalParser.parse("这个月转账多少", context)
        assertNotNull(draft)
        assertEquals(QueryBillType.TRANSFER, draft!!.billType)
    }

    // === 非查询意图测试 ===

    @Test
    fun `非查询意图 - 普通聊天`() {
        val draft = QueryDraftLocalParser.parse("你好", context)
        assertNull(draft)
    }

    @Test
    fun `非查询意图 - 无查询关键词`() {
        val draft = QueryDraftLocalParser.parse("今天天气不错", context)
        assertNull(draft)
    }

    // === 修正检测测试 ===

    @Test
    fun `修正关键词 - 不是X是Y`() {
        val correction = QueryDraftLocalParser.detectCorrection("不是苹果，是水果")
        assertNotNull(correction)
        assertTrue(correction is QueryDraftCorrection.UpdateKeyword)
        assertEquals("水果", (correction as QueryDraftCorrection.UpdateKeyword).newKeyword)
    }

    @Test
    fun `修正时间 - 查上个月`() {
        val correction = QueryDraftLocalParser.detectCorrection("查上个月")
        assertNotNull(correction)
        assertTrue(correction is QueryDraftCorrection.UpdateTime)
    }

    @Test
    fun `修正账本 - 全部账本`() {
        val correction = QueryDraftLocalParser.detectCorrection("全部账本")
        assertNotNull(correction)
        assertTrue(correction is QueryDraftCorrection.UpdateBookScope)
        assertEquals(BookScope.ALL, (correction as QueryDraftCorrection.UpdateBookScope).scope)
    }

    @Test
    fun `修正类型 - 只看收入`() {
        val correction = QueryDraftLocalParser.detectCorrection("只看收入")
        assertNotNull(correction)
        assertTrue(correction is QueryDraftCorrection.UpdateBillType)
        assertEquals(QueryBillType.INCOME, (correction as QueryDraftCorrection.UpdateBillType).billType)
    }

    @Test
    fun `执行指令 - 统计金额`() {
        val correction = QueryDraftLocalParser.detectCorrection("统计金额")
        assertNotNull(correction)
        assertTrue(correction is QueryDraftCorrection.ExecuteStats)
    }

    @Test
    fun `执行指令 - 搜索账单`() {
        val correction = QueryDraftLocalParser.detectCorrection("搜索账单")
        assertNotNull(correction)
        assertTrue(correction is QueryDraftCorrection.ExecuteSearch)
    }

    // === 分类排行测试 ===

    @Test
    fun `分类排行识别`() {
        val draft = QueryDraftLocalParser.parse("这个月支出最多的分类是什么", context)
        assertNotNull(draft)
        assertEquals(QueryType.TOP_CATEGORIES, draft!!.queryType)
    }
}
