package com.taostudio.tapaccounting

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonUtilsTest {
    @Test
    fun parseBuiltInCategories_defaultsMissingTypeBeforeItemsReachSelectionSet() {
        val categories = JsonUtils.parseBuiltInCategories(
            StringReader(
                """
                [
                  {"name":"餐饮","icon":"https://example.com/food.png"}
                ]
                """.trimIndent()
            )
        )

        val selected = linkedSetOf<BuiltInCategory>()
        selected.add(categories.single())

        assertEquals("", selected.single().type)
    }

    @Test
    fun parseBuiltInCategories_skipsEntriesWithoutUsableNameOrIcon() {
        val categories = JsonUtils.parseBuiltInCategories(
            StringReader(
                """
                [
                  {"name":"","icon":"https://example.com/empty-name.png"},
                  {"name":"餐饮"},
                  {"name":"交通","icon":"https://example.com/transit.png"}
                ]
                """.trimIndent()
            )
        )

        assertEquals(listOf("交通"), categories.map { it.name })
    }
}
