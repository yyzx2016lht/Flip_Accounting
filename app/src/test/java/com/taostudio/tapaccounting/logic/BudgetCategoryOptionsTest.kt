package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Category
import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetCategoryOptionsTest {
    @Test
    fun build_keepsIdentityWhenLeafNamesAreEqual() {
        val categories = listOf(
            Category(id = 1, name = "日常", type = 0),
            Category(id = 2, name = "旅行", type = 0),
            Category(id = 3, name = "交通", type = 0, parentId = 1),
            Category(id = 4, name = "交通", type = 0, parentId = 2)
        )

        val options = BudgetCategoryOptions.build("总预算", categories)

        assertEquals(null, options.first().categoryId)
        assertEquals(3L, options.first { it.label == "日常 - 交通" }.categoryId)
        assertEquals(4L, options.first { it.label == "旅行 - 交通" }.categoryId)
    }
}
