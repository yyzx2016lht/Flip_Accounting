package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInCategorySelectionTest {
    private val lawyerFeeOne = BuiltInCategory(
        name = "律师费",
        icon = "https://example.com/lawyer-one.png"
    )
    private val lawyerFeeTwo = BuiltInCategory(
        name = "律师费",
        icon = "https://example.com/lawyer-two.png"
    )

    @Test
    fun selectingOneIconDoesNotSelectAnotherIconWithTheSameName() {
        val selection = BuiltInCategorySelection()

        selection.toggle(lawyerFeeOne)

        assertTrue(selection.isSelected(lawyerFeeOne))
        assertFalse(selection.isSelected(lawyerFeeTwo))
    }

    @Test
    fun selectingAnotherIconWithTheSameNameMovesTheSelection() {
        val selection = BuiltInCategorySelection()
        selection.toggle(lawyerFeeOne)

        selection.toggle(lawyerFeeTwo)

        assertFalse(selection.isSelected(lawyerFeeOne))
        assertTrue(selection.isSelected(lawyerFeeTwo))
        assertEquals(listOf(lawyerFeeTwo), selection.selectedItems())
    }
}
