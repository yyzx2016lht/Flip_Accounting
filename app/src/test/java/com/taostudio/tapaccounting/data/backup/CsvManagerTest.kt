package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.taostudio.tapaccounting.data.local.entity.Bill
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CsvManagerTest {

    @Test
    fun exportAndImport_preservesSpecialBillFields() {
        val originalBills = listOf(
            Bill(
                id = 11L,
                type = Bill.TYPE_EXPENSE,
                amount = 80.0,
                originalAmount = 100.0,
                currency = "USD",
                exchangeRate = 7.2,
                categoryName = "餐饮",
                accountName = "现金",
                time = 1_700_000_000_000,
                remark = "原始消费",
                bookName = "默认账本"
            ),
            Bill(
                id = 12L,
                type = Bill.TYPE_INCOME,
                subType = Bill.SUBTYPE_REFUND,
                amount = 20.0,
                originalAmount = 20.0,
                currency = "USD",
                exchangeRate = 7.2,
                categoryName = "退款：餐饮",
                accountName = "现金",
                time = 1_700_000_100_000,
                remark = "退款",
                bookName = "默认账本",
                relatedBillId = 11L
            ),
            Bill(
                id = 13L,
                type = Bill.TYPE_TRANSFER,
                subType = Bill.SUBTYPE_REPAYMENT,
                amount = 300.0,
                originalAmount = 300.0,
                currency = "CNY",
                exchangeRate = 1.0,
                accountName = "储蓄卡",
                toAccountName = "信用卡",
                time = 1_700_000_200_000,
                remark = "还款",
                fee = 3.0,
                bookName = "默认账本"
            ),
            Bill(
                id = 14L,
                type = Bill.TYPE_EXPENSE,
                subType = Bill.SUBTYPE_NORMAL,
                amount = 50.0,
                originalAmount = 50.0,
                currency = "CNY",
                exchangeRate = 1.0,
                categoryName = "平账",
                accountName = "钱包",
                time = 1_700_000_300_000,
                remark = "不计收支",
                bookName = "默认账本",
                excludeFromStats = true
            )
        )

        val output = ByteArrayOutputStream()
        CsvManager.export(originalBills, output)

        val imported = CsvManager.import(ByteArrayInputStream(output.toByteArray()), fallbackBookName = "备用账本")

        assertEquals(4, imported.size)
        assertEquals(Bill.SUBTYPE_REFUND, imported[1].subType)
        assertEquals(11L, imported[1].relatedBillId)
        assertEquals(7.2, imported[1].exchangeRate, 0.000001)
        assertEquals(Bill.TYPE_TRANSFER, imported[2].type)
        assertEquals(Bill.SUBTYPE_REPAYMENT, imported[2].subType)
        assertEquals(Bill.SUBTYPE_NORMAL, imported[3].subType)
        assertEquals(true, imported[3].excludeFromStats)
    }

    @Test
    fun importFlipCsv_convertsLegacyRepaymentTypeIntoTransferSubtype() {
        val csv = """
            time,id,type,amount,currency,categoryName,accountName,toAccountName,remark,fee,bookName,relatedBillId
            2025-01-01 10:00:00,21,3,128,CNY,还款,储蓄卡,信用卡,旧数据,0,默认账本,
        """.trimIndent()

        val imported = CsvManager.import(ByteArrayInputStream(csv.toByteArray()), fallbackBookName = null)

        assertEquals(1, imported.size)
        assertEquals(Bill.TYPE_TRANSFER, imported.first().type)
        assertEquals(Bill.SUBTYPE_REPAYMENT, imported.first().subType)
        assertNull(imported.first().relatedBillId)
    }

    @Test
    fun importFlipCsv_convertsLegacyBalanceAdjustmentSubtypes() {
        val csv = """
            time,id,type,subType,amount,currency,categoryName,accountName,remark,bookName
            2025-01-01 10:00:00,31,0,3,100,CNY,平账,现金,旧平账(计入),默认账本
            2025-01-01 11:00:00,32,0,4,200,CNY,平账,现金,旧平账(不计入),默认账本
            2025-01-01 12:00:00,33,1,3,300,CNY,平账,现金,旧收入平账(计入),默认账本
        """.trimIndent()

        val imported = CsvManager.import(ByteArrayInputStream(csv.toByteArray()), fallbackBookName = null)

        assertEquals(3, imported.size)
        // subType=3 -> SUBTYPE_NORMAL, excludeFromStats=false
        assertEquals(Bill.SUBTYPE_NORMAL, imported[0].subType)
        assertEquals(false, imported[0].excludeFromStats)
        assertEquals(Bill.TYPE_EXPENSE, imported[0].type)
        // subType=4 -> SUBTYPE_NORMAL, excludeFromStats=true
        assertEquals(Bill.SUBTYPE_NORMAL, imported[1].subType)
        assertEquals(true, imported[1].excludeFromStats)
        assertEquals(Bill.TYPE_EXPENSE, imported[1].type)
        // subType=3 + type=1 -> SUBTYPE_NORMAL, excludeFromStats=false, type保留
        assertEquals(Bill.SUBTYPE_NORMAL, imported[2].subType)
        assertEquals(false, imported[2].excludeFromStats)
        assertEquals(Bill.TYPE_INCOME, imported[2].type)
    }

    @Test
    fun importFlipCsv_readsExcludeFromStatsColumn() {
        val csv = """
            time,id,type,subType,amount,currency,categoryName,accountName,remark,bookName,excludeFromStats
            2025-01-01 10:00:00,41,0,0,100,CNY,餐饮,现金,正常消费,默认账本,0
            2025-01-01 11:00:00,42,0,0,200,CNY,平账,现金,不计入,默认账本,1
        """.trimIndent()

        val imported = CsvManager.import(ByteArrayInputStream(csv.toByteArray()), fallbackBookName = null)

        assertEquals(2, imported.size)
        assertEquals(false, imported[0].excludeFromStats)
        assertEquals(true, imported[1].excludeFromStats)
    }

    @Test
    fun importQianJi_mapsBuJiShouZhiToNormalWithExcludeFromStats() {
        val csv = """
            时间,分类,二级分类,类型,金额,币种,账户1,账户2,备注,手续费
            2025-01-01 10:00:00,平账,,不计收支,50,CNY,钱包,,测试不计收支,0
        """.trimIndent()

        val imported = CsvManager.import(ByteArrayInputStream(csv.toByteArray()), fallbackBookName = null)

        assertEquals(1, imported.size)
        assertEquals(Bill.TYPE_EXPENSE, imported[0].type)
        assertEquals(Bill.SUBTYPE_NORMAL, imported[0].subType)
        assertEquals(true, imported[0].excludeFromStats)
    }
}

