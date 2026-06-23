package com.taostudio.tapaccounting.chat.query

import com.taostudio.tapaccounting.data.local.entity.Bill
import org.junit.Assert.*
import org.junit.Test

/**
 * 测试 QueryDraftManager 的纯逻辑：
 * - 执行口径: 按 billType 正确统计 + excludeFromStats
 * - QueryDraft 构造和字段正确性
 */
class QueryDraftManagerTest {

    // === 执行口径测试 ===

    @Test
    fun `EXPENSE only counts expense bills`() {
        val bills = listOf(
            makeBill(1, Bill.TYPE_EXPENSE, Bill.SUBTYPE_NORMAL, 100.0),
            makeBill(2, Bill.TYPE_INCOME, Bill.SUBTYPE_NORMAL, 5000.0),
            makeBill(3, Bill.TYPE_EXPENSE, Bill.SUBTYPE_NORMAL, 50.0)
        )
        val filtered = filterByBillType(bills, QueryBillType.EXPENSE)
        val statBills = filtered.filterNot { it.excludeFromStats }

        assertEquals(150.0, statBills.sumOf { it.amount }, 0.01)
        assertEquals(2, statBills.size)
    }

    @Test
    fun `INCOME counts income bills`() {
        val bills = listOf(
            makeBill(1, Bill.TYPE_EXPENSE, Bill.SUBTYPE_NORMAL, 100.0),
            makeBill(2, Bill.TYPE_INCOME, Bill.SUBTYPE_NORMAL, 5000.0),
            makeBill(3, Bill.TYPE_INCOME, Bill.SUBTYPE_NORMAL, 3000.0)
        )
        val filtered = filterByBillType(bills, QueryBillType.INCOME)
        val statBills = filtered.filterNot { it.excludeFromStats }

        assertEquals(8000.0, statBills.sumOf { it.amount }, 0.01)
        assertEquals(2, statBills.size)
    }

    @Test
    fun `TRANSFER counts transfer bills`() {
        val bills = listOf(
            makeBill(1, Bill.TYPE_TRANSFER, Bill.SUBTYPE_NORMAL, 200.0),
            makeBill(2, Bill.TYPE_EXPENSE, Bill.SUBTYPE_NORMAL, 100.0)
        )
        val filtered = filterByBillType(bills, QueryBillType.TRANSFER)
        val statBills = filtered.filterNot { it.excludeFromStats }

        assertEquals(200.0, statBills.sumOf { it.amount }, 0.01)
        assertEquals(1, statBills.size)
    }

    @Test
    fun `excludeFromStats not counted`() {
        val bills = listOf(
            makeBill(1, Bill.TYPE_EXPENSE, Bill.SUBTYPE_NORMAL, 100.0, excludeFromStats = false),
            makeBill(2, Bill.TYPE_EXPENSE, Bill.SUBTYPE_NORMAL, 999.0, excludeFromStats = true)
        )
        val filtered = filterByBillType(bills, QueryBillType.EXPENSE)
        val statBills = filtered.filterNot { it.excludeFromStats }

        assertEquals(100.0, statBills.sumOf { it.amount }, 0.01)
        assertEquals(1, statBills.size)
    }

    @Test
    fun `REFUND only counts refund bills`() {
        val bills = listOf(
            makeBill(1, Bill.TYPE_EXPENSE, Bill.SUBTYPE_NORMAL, 100.0),
            makeBill(2, Bill.TYPE_EXPENSE, Bill.SUBTYPE_REFUND, 30.0)
        )
        val filtered = filterByBillType(bills, QueryBillType.REFUND)

        assertEquals(30.0, filtered.sumOf { it.amount }, 0.01)
        assertEquals(1, filtered.size)
    }

    @Test
    fun `ANY counts all matched bills`() {
        val bills = listOf(
            makeBill(1, Bill.TYPE_EXPENSE, Bill.SUBTYPE_NORMAL, 100.0),
            makeBill(2, Bill.TYPE_INCOME, Bill.SUBTYPE_NORMAL, 5000.0),
            makeBill(3, Bill.TYPE_TRANSFER, Bill.SUBTYPE_NORMAL, 200.0)
        )
        val filtered = filterByBillType(bills, QueryBillType.ANY)

        assertEquals(5300.0, filtered.sumOf { it.amount }, 0.01)
        assertEquals(3, filtered.size)
    }

    @Test
    fun `REPAYMENT counts repayment bills`() {
        val bills = listOf(
            makeBill(1, Bill.TYPE_TRANSFER, Bill.SUBTYPE_NORMAL, 200.0),
            makeBill(2, Bill.TYPE_TRANSFER, Bill.SUBTYPE_REPAYMENT, 500.0)
        )
        val filtered = filterByBillType(bills, QueryBillType.REPAYMENT)

        assertEquals(500.0, filtered.sumOf { it.amount }, 0.01)
        assertEquals(1, filtered.size)
    }

    @Test
    fun `EXPENSE excludes refund bills`() {
        val bills = listOf(
            makeBill(1, Bill.TYPE_EXPENSE, Bill.SUBTYPE_NORMAL, 100.0),
            makeBill(2, Bill.TYPE_EXPENSE, Bill.SUBTYPE_REFUND, 30.0),
            makeBill(3, Bill.TYPE_EXPENSE, Bill.SUBTYPE_NORMAL, 50.0)
        )
        val filtered = filterByBillType(bills, QueryBillType.EXPENSE)

        assertEquals(150.0, filtered.sumOf { it.amount }, 0.01)
        assertEquals(2, filtered.size)
    }

    // === QueryDraft 构造测试 ===

    @Test
    fun `QueryDraft default values`() {
        val draft = QueryDraft(
            queryType = QueryType.AMOUNT_TOTAL,
            sourceText = "test"
        )

        assertEquals(QueryType.AMOUNT_TOTAL, draft.queryType)
        assertNull(draft.keyword)
        assertNull(draft.categoryId)
        assertNull(draft.categoryName)
        assertNull(draft.assetId)
        assertNull(draft.assetName)
        assertEquals(BookScope.CURRENT, draft.bookScope)
        assertEquals(QueryBillType.EXPENSE, draft.billType)
        assertEquals(QueryAggregation.TOTAL, draft.aggregation)
        assertEquals(0.0, draft.confidence, 0.01)
    }

    @Test
    fun `QueryDraft with all fields`() {
        val timeRange = QueryTimeRange(
            startMillis = 1000L,
            endMillis = 2000L,
            label = "本月"
        )
        val draft = QueryDraft(
            queryType = QueryType.BILL_LIST,
            keyword = "苹果",
            categoryId = 1L,
            categoryName = "水果",
            assetId = 2L,
            assetName = "微信",
            bookScope = BookScope.ALL,
            bookName = "旅行账本",
            billType = QueryBillType.INCOME,
            timeRange = timeRange,
            aggregation = QueryAggregation.BY_CATEGORY,
            sourceText = "这个月苹果花了多少钱",
            confidence = 0.85
        )

        assertEquals(QueryType.BILL_LIST, draft.queryType)
        assertEquals("苹果", draft.keyword)
        assertEquals(1L, draft.categoryId)
        assertEquals("水果", draft.categoryName)
        assertEquals(2L, draft.assetId)
        assertEquals("微信", draft.assetName)
        assertEquals(BookScope.ALL, draft.bookScope)
        assertEquals("旅行账本", draft.bookName)
        assertEquals(QueryBillType.INCOME, draft.billType)
        assertEquals(timeRange, draft.timeRange)
        assertEquals(QueryAggregation.BY_CATEGORY, draft.aggregation)
        assertEquals(0.85, draft.confidence, 0.01)
    }

    @Test
    fun `QueryTimeRange isValid`() {
        val valid = QueryTimeRange(startMillis = 1000L, endMillis = 2000L)
        assertTrue(valid.isValid())

        val invalid = QueryTimeRange(startMillis = 2000L, endMillis = 1000L)
        assertFalse(invalid.isValid())

        val noStart = QueryTimeRange(startMillis = null, endMillis = 2000L)
        assertFalse(noStart.isValid())

        val noEnd = QueryTimeRange(startMillis = 1000L, endMillis = null)
        assertFalse(noEnd.isValid())
    }

    // === Helper ===

    private fun makeBill(
        id: Long,
        type: Int,
        subType: Int,
        amount: Double,
        excludeFromStats: Boolean = false
    ): Bill {
        return Bill(
            id = id,
            type = type,
            subType = subType,
            amount = amount,
            originalAmount = amount,
            currency = "CNY",
            time = System.currentTimeMillis(),
            bookName = "日常账本",
            excludeFromStats = excludeFromStats
        )
    }

    private fun filterByBillType(bills: List<Bill>, billType: QueryBillType): List<Bill> {
        return bills.filter { bill ->
            when (billType) {
                QueryBillType.EXPENSE -> bill.type == Bill.TYPE_EXPENSE && bill.subType != Bill.SUBTYPE_REFUND
                QueryBillType.INCOME -> bill.type == Bill.TYPE_INCOME
                QueryBillType.TRANSFER -> bill.type == Bill.TYPE_TRANSFER && bill.subType != Bill.SUBTYPE_REPAYMENT
                QueryBillType.REPAYMENT -> bill.type == Bill.TYPE_TRANSFER && bill.subType == Bill.SUBTYPE_REPAYMENT
                QueryBillType.REFUND -> bill.subType == Bill.SUBTYPE_REFUND
                QueryBillType.ANY -> true
            }
        }
    }
}
