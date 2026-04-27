package tao.test.flipaccounting.chat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AiTimeRangeParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 4, 27)

    @Test
    fun parseLastMonth() {
        val range = AiTimeRangeParser.parse("上个月微信餐饮支出是多少", today, zone)

        assertNotNull(range)
        assertEquals("上个月", range!!.phrase)
        assertEquals(
            LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            range.startMillis
        )
        assertEquals(
            LocalDate.of(2026, 4, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L,
            range.endMillis
        )
    }

    @Test
    fun parseSupportedRelativeRanges() {
        assertEquals("今天", AiTimeRangeParser.parse("今天花了多少", today, zone)?.phrase)
        assertEquals("昨天", AiTimeRangeParser.parse("昨天支出", today, zone)?.phrase)
        assertEquals("本月", AiTimeRangeParser.parse("本月支出", today, zone)?.phrase)
        assertEquals("今年", AiTimeRangeParser.parse("今年支出", today, zone)?.phrase)
    }
}
