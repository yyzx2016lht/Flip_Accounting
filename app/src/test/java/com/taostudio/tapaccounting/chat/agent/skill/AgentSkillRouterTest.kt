package com.taostudio.tapaccounting.chat.agent.skill

import org.junit.Assert.*
import org.junit.Test

class AgentSkillRouterTest {

    @Test
    fun `route empty text returns general`() {
        val result = AgentSkillRouter.route("", null)
        assertEquals(listOf("general"), result)
    }

    @Test
    fun `route blank text returns general`() {
        val result = AgentSkillRouter.route("   ", null)
        assertEquals(listOf("general"), result)
    }

    @Test
    fun `route bill keywords returns bill skill`() {
        val result = AgentSkillRouter.route("午饭花了35", null)
        assertTrue(result.contains("bill"))
    }

    @Test
    fun `route stats keywords returns stats skill`() {
        val result = AgentSkillRouter.route("本月餐饮花了多少", null)
        assertTrue(result.contains("stats"))
    }

    @Test
    fun `route asset keywords returns asset_book skill`() {
        val result = AgentSkillRouter.route("微信还有多少钱", null)
        assertTrue(result.contains("asset_book"))
    }

    @Test
    fun `route settings keywords returns settings skill`() {
        val result = AgentSkillRouter.route("关闭震动", null)
        assertTrue(result.contains("settings"))
    }

    @Test
    fun `route nav to stats page returns general and stats`() {
        val result = AgentSkillRouter.route("打开统计页", null)
        assertTrue(result.contains("general"))
        assertTrue(result.contains("stats"))
    }

    @Test
    fun `route unknown text returns general`() {
        val result = AgentSkillRouter.route("你好", null)
        assertEquals(listOf("general"), result)
    }

    @Test
    fun `route with fallback never returns empty`() {
        val result = AgentSkillRouter.routeWithFallback("", null)
        assertFalse(result.isEmpty())
        assertTrue(result.contains("general"))
    }
}
