package tao.test.flipaccounting.chat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiIntentRouterTest {
    @Test
    fun routeExpenseQueryWithSlots() {
        val route = AiIntentRouter.route("搜索微信上个月在吃的上花了多少钱")

        assertEquals(AiIntentType.QUERY, route.intentType)
        assertTrue(route.confidence >= 0.8)
        assertNotNull(route.slots.timeRange)
        assertEquals("微信", route.slots.account)
        assertEquals("餐饮", route.slots.category)
    }

    @Test
    fun routeBookkeepingKeepsExistingAccountingFlow() {
        val route = AiIntentRouter.route("午餐花了28元，微信支付")

        assertEquals(AiIntentType.BOOKKEEPING, route.intentType)
        assertEquals("微信", route.slots.account)
        assertEquals("餐饮", route.slots.category)
        assertEquals(28.0, route.slots.amount ?: 0.0, 0.001)
    }

    @Test
    fun routeHighRiskWriteAsUnknown() {
        val route = AiIntentRouter.route("删除上个月所有微信餐饮账单")

        assertEquals(AiIntentType.UNKNOWN, route.intentType)
        assertTrue(route.confidence >= 0.9)
    }

    @Test
    fun routeVisaAndMealAliases() {
        val route = AiIntentRouter.route("帮我查一下上个月visa卡在吃的上面花了多少钱")

        assertEquals(AiIntentType.QUERY, route.intentType)
        assertEquals("visa卡", route.slots.account)
        assertEquals("餐饮", route.slots.category)

        val mealRoute = AiIntentRouter.route("帮我查一下这个月三餐花了多少钱")
        assertEquals(AiIntentType.QUERY, mealRoute.intentType)
        assertEquals("餐饮", mealRoute.slots.category)
    }
}
