package com.taostudio.tapaccounting.data.repository

import com.taostudio.tapaccounting.data.local.entity.Category
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryRepositoryTest {
    @Test
    fun displayNamesById_usesCurrentParentAndChildNames() {
        val categories = listOf(
            Category(id = 1, name = "餐饮", type = 0),
            Category(id = 2, name = "早餐", type = 0, parentId = 1)
        )

        val names = CategoryRepository.displayNamesById(categories)

        assertEquals("餐饮", names[1L])
        assertEquals("餐饮 - 早餐", names[2L])
    }

    @Test
    fun displayNamesById_keepsDuplicateLeafNamesDistinctById() {
        val categories = listOf(
            Category(id = 1, name = "日常", type = 0),
            Category(id = 2, name = "旅行", type = 0),
            Category(id = 3, name = "交通", type = 0, parentId = 1),
            Category(id = 4, name = "交通", type = 0, parentId = 2)
        )

        val names = CategoryRepository.displayNamesById(categories)

        assertEquals("日常 - 交通", names[3L])
        assertEquals("旅行 - 交通", names[4L])
    }
}
