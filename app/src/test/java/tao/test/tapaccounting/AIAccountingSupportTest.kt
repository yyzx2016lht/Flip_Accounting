package tao.test.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Test

class AIAccountingSupportTest {
    private val expenseCats = listOf(
        "吃的",
        "吃的/::/水果",
        "吃的/::/买菜",
        "喝的",
        "喝的/::/饮料",
        "其他"
    )

    @Test
    fun findBestMatchMapsSlashSeparatedChildPath() {
        val matched = findBestMatch("吃的/水果", expenseCats)

        assertEquals("吃的/::/水果", matched)
    }

    @Test
    fun findBestMatchMapsLeafNameToFullChildPath() {
        val matched = findBestMatch("水果", expenseCats)

        assertEquals("吃的/::/水果", matched)
    }

    @Test
    fun normalizeCategoryPathSupportsCommonSeparators() {
        val normalized = normalizeCategoryPath(" 吃的 -> 水果 ")

        assertEquals("吃的/::/水果", normalized)
    }
}
