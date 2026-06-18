package com.taostudio.tapaccounting.chat.agent

import com.taostudio.tapaccounting.chat.agent.tool.AgentUnsupportedTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUnsupportedToolTest {

    @Test
    fun `unsupported tool returns standard message without feature name`() {
        assertEquals("该功能尚未实现", AgentUnsupportedTool.formatMessage(""))
    }

    @Test
    fun `unsupported tool identifies requested feature`() {
        assertEquals(
            "“永久删除账单”功能尚未实现",
            AgentUnsupportedTool.formatMessage("永久删除账单")
        )
    }

    @Test
    fun `general skill always includes unsupported fallback`() {
        assertTrue(
            com.taostudio.tapaccounting.chat.agent.skill.BuiltInAgentSkills.general.toolIds
                .contains("agent.unsupported")
        )
    }

}
