package tao.test.flipaccounting.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tao.test.flipaccounting.data.local.entity.Bill
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
                subType = Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED,
                amount = 50.0,
                originalAmount = 50.0,
                currency = "CNY",
                exchangeRate = 1.0,
                categoryName = "平账",
                accountName = "钱包",
                time = 1_700_000_300_000,
                remark = "不计收支",
                bookName = "默认账本"
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
        assertEquals(Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED, imported[3].subType)
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
}
