package com.taostudio.tapaccounting.chat.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentConfirmationTest {

    @Before
    fun setUp() {
        PendingActionManager.clear("test-conversation")
        PendingActionManager.clear("conv1")
        PendingActionManager.clear("conv2")
        PendingActionManager.clear("conv3")
        PendingActionManager.clear("conv4")
        ConversationStateManager.clearAll()
    }

    @Test
    fun `read tool never requires confirmation`() {
        val tool = createReadTool()
        assertFalse(AgentConfirmationController.shouldConfirm(tool, org.json.JSONObject()))
    }

    @Test
    fun `delete tool always requires confirmation`() {
        val tool = createBillDeleteTool()
        assertTrue(AgentConfirmationController.shouldConfirm(tool, org.json.JSONObject()))
    }

    @Test
    fun `DESTRUCTIVE tool always requires confirmation`() {
        val tool = createBillDeleteTool()
        assertTrue(AgentConfirmationController.shouldConfirm(tool, org.json.JSONObject()))
    }

    @Test
    fun `pending action clear`() {
        val action = PendingAgentAction.create(
            conversationId = "conv2",
            toolId = "bill.delete",
            params = org.json.JSONObject(),
            preview = "test"
        )
        PendingActionManager.save(action)
        PendingActionManager.clear("conv2")
        assertNull(PendingActionManager.get("conv2"))
    }

    @Test
    fun `pending action without remaining calls`() {
        val action = PendingAgentAction.create(
            conversationId = "conv4",
            toolId = "bill.delete",
            params = org.json.JSONObject(),
            preview = "test"
        )
        assertFalse(action.hasRemainingCalls())
    }

    @Test
    fun `pending action has remaining calls`() {
        val remainingCalls = listOf(
            ChatAgentOrchestrator.ToolCall("bill.delete", org.json.JSONObject(), "")
        )
        val action = PendingAgentAction.create(
            conversationId = "conv3",
            toolId = "bill.delete",
            params = org.json.JSONObject(),
            preview = "删除账单 123",
            remainingCalls = remainingCalls
        )
        assertTrue(action.hasRemainingCalls())
        assertEquals(1, action.remainingCalls.size)
    }

    @Test
    fun `pending expired returns null`() {
        val action = PendingAgentAction(
            conversationId = "conv1",
            toolId = "bill.delete",
            params = org.json.JSONObject(),
            preview = "test",
            createdAt = System.currentTimeMillis() - 10_000,
            expiresAt = System.currentTimeMillis() - 1000
        )
        PendingActionManager.save(action)
        assertNull(PendingActionManager.get("conv1"))
    }

    @Test
    fun `hasPending returns false for expired action`() {
        val action = PendingAgentAction(
            conversationId = "conv1",
            toolId = "bill.delete",
            params = org.json.JSONObject(),
            preview = "test",
            createdAt = System.currentTimeMillis() - 10_000,
            expiresAt = System.currentTimeMillis() - 1000
        )
        PendingActionManager.save(action)
        assertFalse(PendingActionManager.hasPending("conv1"))
    }

    @Test
    fun `hasPending returns true for active action`() {
        val action = PendingAgentAction.create(
            conversationId = "conv1",
            toolId = "bill.delete",
            params = org.json.JSONObject(),
            preview = "test"
        )
        PendingActionManager.save(action)
        assertTrue(PendingActionManager.hasPending("conv1"))
    }

    @Test
    fun `hasPending returns false when no action exists`() {
        assertFalse(PendingActionManager.hasPending("nonexistent"))
    }

    private fun createBillDeleteTool(): AgentTool {
        return object : AgentTool {
            override val id = "bill.delete"
            override val category = "记账"
            override val risk = RiskLevel.DESTRUCTIVE
            override val description = "删除账单"
            override val parameterSchema = org.json.JSONObject()
            override suspend fun execute(params: org.json.JSONObject, context: AgentSessionContext): AgentToolResult {
                return AgentToolResult.success()
            }
        }
    }

    private fun createReadTool(): AgentTool {
        return object : AgentTool {
            override val id = "bill.list_recent"
            override val category = "记账"
            override val risk = RiskLevel.READ
            override val description = "查询账单"
            override val parameterSchema = org.json.JSONObject()
            override suspend fun execute(params: org.json.JSONObject, context: AgentSessionContext): AgentToolResult {
                return AgentToolResult.success()
            }
        }
    }

    private fun createBillCreateTool(): AgentTool {
        return object : AgentTool {
            override val id = "bill.create_from_text"
            override val category = "记账"
            override val risk = RiskLevel.WRITE
            override val description = "记账"
            override val parameterSchema = org.json.JSONObject()
            override suspend fun execute(params: org.json.JSONObject, context: AgentSessionContext): AgentToolResult {
                return AgentToolResult.success()
            }
        }
    }
}
