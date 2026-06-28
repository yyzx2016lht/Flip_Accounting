package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptVisionSummaryTest {

    @Test
    fun `mergeOrphanMetadataLines attaches receipt time to first transaction`() {
        val input = listOf(
            "购买番茄花了 0.52 PLN",
            "2024-06-01 12:30:00",
            "购买面包花了 3.20 PLN"
        )
        val merged = ReceiptImageInputHelper.mergeOrphanMetadataLines(input)
        assertEquals(2, merged.size)
        assertTrue(merged[0].contains("2024-06-01 12:30:00"))
        assertFalse(merged[1].contains("2024-06-01"))
    }

    @Test
    fun `normalizeVisionSummary keeps duplicate products as separate lines`() {
        val raw = """
            购买可乐花了 3.00 PLN
            购买西瓜花了 5.00 PLN
            购买可乐花了 2.00 PLN
        """.trimIndent()

        val normalized = ReceiptImageInputHelper.normalizeVisionSummary(raw)
        val lines = normalized.lineSequence().toList()
        assertEquals(3, lines.size)
    }

    @Test
    fun `normalizeVisionSummary keeps one transaction per line`() {
        val raw = """
            购买番茄花了 0.52 PLN，用了 Visa 支付
            时间 2024-06-01 12:30:00
            购买面包花了 3.20 PLN，用了 Visa 支付
        """.trimIndent()

        val normalized = ReceiptImageInputHelper.normalizeVisionSummary(raw)
        val lines = normalized.lineSequence().toList()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("番茄"))
        assertTrue(lines[0].contains("2024-06-01 12:30:00"))
    }
}
