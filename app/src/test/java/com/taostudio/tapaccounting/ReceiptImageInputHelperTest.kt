package com.taostudio.tapaccounting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptImageInputHelperTest {
    @Test
    fun imageDraftSupplementDoesNotForceAssetName() {
        val input = ReceiptImageInputHelper.buildAccountingInputFromImageDraft(
            draft = "购买晚餐花了 30 CNY",
            supplement = "这是和朋友聚餐"
        )

        assertTrue(input.contains("用户补充（高优先级）"))
        assertTrue(input.contains("不要把补充内容强行解释为支付方式"))
        assertFalse(input.contains("asset_name"))
    }
}
