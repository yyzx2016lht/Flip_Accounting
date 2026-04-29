package tao.test.flipaccounting.chat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiIntentRouterTest {
    @Test
    fun routeExpenseQuery() {
        val route = AiIntentRouter.route("搜索微信上个月在吃的上花了多少钱")

        assertEquals(AiIntentType.BOOKKEEPING_QUERY, route.intentType)
        assertTrue(route.confidence >= 0.45)
        assertNotNull(route.slots.timeRange)
        assertEquals("微信", route.slots.account)
    }

    @Test
    fun routeBookkeepingKeepsExistingAccountingFlow() {
        val route = AiIntentRouter.route("午餐花了28元，微信支付")

        assertEquals(AiIntentType.BOOKKEEPING_CREATE, route.intentType)
        assertEquals(28.0, route.slots.amount ?: 0.0, 0.001)
    }

    @Test
    fun routeHighRiskWriteAsUnknown() {
        val route = AiIntentRouter.route("删除上个月所有微信餐饮账单")

        assertEquals(AiIntentType.BOOKKEEPING_DELETE, route.intentType)
        assertTrue(route.confidence >= 0.9)
    }

    @Test
    fun routeVisaQueryWithoutHardcodedCategory() {
        val route = AiIntentRouter.route("帮我查一下上个月visa卡在吃的上面花了多少钱")

        assertEquals(AiIntentType.BOOKKEEPING_QUERY, route.intentType)
        assertEquals("visa卡", route.slots.account)

        val mealRoute = AiIntentRouter.route("帮我查一下这个月三餐花了多少钱")
        assertEquals(AiIntentType.BOOKKEEPING_QUERY, mealRoute.intentType)
        assertNotNull(mealRoute.slots.timeRange)
    }

    @Test
    fun requiredQueryPhrasesStayLocalDbQueries() {
        listOf("本月花了多少", "上周餐饮支出", "上一笔是什么", "今天支付宝花了多少").forEach { text ->
            assertEquals(text, AiIntentType.BOOKKEEPING_QUERY, AiIntentRouter.route(text).intentType)
        }
    }

    @Test
    fun modifyAndBulkOverwriteAreConservative() {
        assertEquals(AiIntentType.BOOKKEEPING_UPDATE, AiIntentRouter.route("把上一笔改成餐饮 20 元").intentType)
        assertEquals(AiIntentType.UNKNOWN, AiIntentRouter.route("全部改成餐饮").intentType)
    }

    @Test
    fun destructiveOrBulkPhrasesStayConservative() {
        assertEquals(AiIntentType.BOOKKEEPING_DELETE, AiIntentRouter.route("删除这周餐饮").intentType)
        assertTrue(
            AiIntentRouter.route("清空聊天记录").intentType in setOf(
                AiIntentType.BOOKKEEPING_DELETE,
                AiIntentType.SESSION_UPDATE,
                AiIntentType.UNKNOWN
            )
        )
        assertEquals(AiIntentType.UNKNOWN, AiIntentRouter.route("批量修改成餐饮").intentType)
        assertEquals(AiIntentType.UNKNOWN, AiIntentRouter.route("全部改成收入").intentType)
    }
}
