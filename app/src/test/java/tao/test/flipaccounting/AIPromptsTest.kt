package tao.test.flipaccounting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIPromptsTest {
    @Test
    fun intentRouterPromptIncludesQueryRuleWhenEnabled() {
        val prompt = AIPrompts.buildIntentRouterPrompt(enableQuery = true)

        assertTrue(prompt.contains("应输出 QUERY"))
        assertFalse(prompt.contains("当前已禁用 Query 功能"))
    }

    @Test
    fun intentRouterPromptRemovesQueryRoutingWhenDisabled() {
        val prompt = AIPrompts.buildIntentRouterPrompt(enableQuery = false)

        assertTrue(prompt.contains("当前已禁用 Query 功能"))
        assertTrue(prompt.contains("查询/统计类请求"))
        assertFalse(prompt.contains("应输出 QUERY（或 BOOKKEEPING_QUERY 兼容语义）"))
    }
}
