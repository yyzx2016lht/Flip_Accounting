package com.taostudio.tapaccounting.chat.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentRegistryTest {

    @Before
    fun setUp() {
        AgentToolRegistry.clear()
    }

    @Test
    fun `register same tool twice with same class updates instance`() {
        val tool1 = createDummyTool("test.tool", "v1")
        val tool2 = createDummyTool("test.tool", "v2")
        AgentToolRegistry.register(tool1)
        AgentToolRegistry.register(tool2)
        assertEquals(1, AgentToolRegistry.getAll().size)
        assertEquals("v2", AgentToolRegistry.findById("test.tool")?.description)
    }

    @Test
    fun `findById returns null for unknown tool`() {
        assertNull(AgentToolRegistry.findById("nonexistent.tool"))
    }

    @Test
    fun `getAll returns all registered tools`() {
        AgentToolRegistry.register(createDummyTool("tool1"))
        AgentToolRegistry.register(createDummyTool("tool2"))
        assertEquals(2, AgentToolRegistry.getAll().size)
    }

    @Test
    fun `clear removes all tools`() {
        AgentToolRegistry.register(createDummyTool("tool1"))
        AgentToolRegistry.register(createDummyTool("tool2"))
        AgentToolRegistry.clear()
        assertEquals(0, AgentToolRegistry.getAll().size)
    }

    private fun createDummyTool(toolId: String, description: String = "Test tool"): AgentTool {
        return object : AgentTool {
            override val id = toolId
            override val category = "test"
            override val risk = RiskLevel.READ
            override val description = description
            override val parameterSchema = org.json.JSONObject()
            override suspend fun execute(params: org.json.JSONObject, context: AgentSessionContext): AgentToolResult {
                return AgentToolResult.success()
            }
        }
    }
}
