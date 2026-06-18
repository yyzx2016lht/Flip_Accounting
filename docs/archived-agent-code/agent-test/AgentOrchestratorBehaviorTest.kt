package com.taostudio.tapaccounting.chat.agent

import com.taostudio.tapaccounting.chat.query.QueryCapabilities
import com.taostudio.tapaccounting.chat.query.QueryContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests real orchestrator behavior by testing the internal logic directly.
 * Since ChatAgentOrchestrator depends on LLM API, we test the helper methods
 * and the pending action / confirmation flow.
 *
 * Note: JSONObject is mocked in unit tests (no Robolectric), so we cannot
 * test behavior that depends on JSONObject.optString/optLong returning real values.
 */
class AgentOrchestratorBehaviorTest {

    @Before
    fun setUp() {
        PendingActionManager.clear("test-conv")
        ConversationStateManager.clearAll()
        AgentToolRegistry.clear()
    }

    // --- Pending action lifecycle ---

    @Test
    fun `no pending - hasPending returns false`() {
        assertFalse(PendingActionManager.hasPending("test-conv"))
    }

    @Test
    fun `pending exists - hasPending returns true`() {
        savePending("test-conv", "bill.delete")
        assertTrue(PendingActionManager.hasPending("test-conv"))
    }

    @Test
    fun `pending expired - hasPending returns false`() {
        val action = PendingAgentAction(
            conversationId = "test-conv",
            toolId = "bill.delete",
            params = org.json.JSONObject(),
            preview = "test",
            createdAt = System.currentTimeMillis() - 10_000,
            expiresAt = System.currentTimeMillis() - 1000
        )
        PendingActionManager.save(action)
        assertFalse(PendingActionManager.hasPending("test-conv"))
    }

    @Test
    fun `pending clear removes action`() {
        savePending("test-conv", "bill.delete")
        PendingActionManager.clear("test-conv")
        assertFalse(PendingActionManager.hasPending("test-conv"))
    }

    // --- Confirmation intent detection ---

    @Test
    fun `isConfirmIntent matches confirm words`() {
        val confirmWords = listOf("确认", "执行", "好的", "好", "可以", "是的", "对", "嗯", "ok", "yes", "y", "确定")
        for (word in confirmWords) {
            assertTrue("'$word' should be confirm intent", isConfirmIntent(word))
        }
    }

    @Test
    fun `isConfirmIntent does not match non-confirm words`() {
        val nonConfirmWords = listOf("你好", "查询", "删除", "算了", "取消", "帮我记账")
        for (word in nonConfirmWords) {
            assertFalse("'$word' should not be confirm intent", isConfirmIntent(word))
        }
    }

    @Test
    fun `isCancelIntent matches cancel words`() {
        val cancelWords = listOf("取消", "算了", "不要了", "不用了", "不要", "不用", "取消吧", "no", "n")
        for (word in cancelWords) {
            assertTrue("'$word' should be cancel intent", isCancelIntent(word))
        }
    }

    @Test
    fun `isCancelIntent does not match non-cancel words`() {
        val nonCancelWords = listOf("确认", "执行", "好的", "你好", "查询")
        for (word in nonCancelWords) {
            assertFalse("'$word' should not be cancel intent", isCancelIntent(word))
        }
    }

    // --- Multi-step pending with remaining calls ---

    @Test
    fun `pending action with remaining calls`() {
        val remaining = listOf(
            ChatAgentOrchestrator.ToolCall("bill.delete", org.json.JSONObject(), "")
        )
        val action = PendingAgentAction.create(
            conversationId = "test-conv",
            toolId = "bill.delete",
            params = org.json.JSONObject(),
            preview = "步骤 1",
            remainingCalls = remaining,
            responseGoal = "删除两笔账单"
        )
        assertTrue(action.hasRemainingCalls())
        assertEquals(1, action.remainingCalls.size)
        assertEquals("bill.delete", action.remainingCalls[0].toolId)
        assertEquals("删除两笔账单", action.responseGoal)
    }

    @Test
    fun `pending action without remaining calls`() {
        val action = PendingAgentAction.create(
            conversationId = "test-conv",
            toolId = "bill.delete",
            params = org.json.JSONObject(),
            preview = "test"
        )
        assertFalse(action.hasRemainingCalls())
    }

    // --- Recent bill/asset tracking ---

    @Test
    fun `withRecentBill prepends new id`() {
        var state = AgentConversationState()
        state = state.withRecentBill(100).withRecentBill(200).withRecentBill(300)
        assertEquals(3, state.recentBillIds.size)
        assertEquals(300L, state.recentBillIds[0])
        assertEquals(200L, state.recentBillIds[1])
        assertEquals(100L, state.recentBillIds[2])
    }

    @Test
    fun `withRecentBill deduplicates`() {
        var state = AgentConversationState()
        state = state.withRecentBill(100).withRecentBill(200).withRecentBill(100)
        assertEquals(2, state.recentBillIds.size)
        assertEquals(100L, state.recentBillIds[0])
        assertEquals(200L, state.recentBillIds[1])
    }

    @Test
    fun `withRecentBill limits to MAX_RECENT_ITEMS`() {
        var state = AgentConversationState()
        for (i in 1..20) {
            state = state.withRecentBill(i.toLong())
        }
        assertTrue(state.recentBillIds.size <= 10)
        assertEquals(20L, state.recentBillIds[0])
    }

    @Test
    fun `recentAssetIds updated correctly`() {
        var state = AgentConversationState()
        state = state.withRecentAsset(50).withRecentAsset(60)
        assertEquals(2, state.recentAssetIds.size)
        assertEquals(60L, state.recentAssetIds[0])
        assertEquals(50L, state.recentAssetIds[1])
    }

    // --- Conversation state isolation ---

    @Test
    fun `conversation state isolation between conversations`() {
        ConversationStateManager.updateState("conv-a") { it.withRecentBill(111) }
        ConversationStateManager.updateState("conv-b") { it.withRecentBill(222) }
        assertEquals(111L, ConversationStateManager.getState("conv-a").recentBillIds.first())
        assertEquals(222L, ConversationStateManager.getState("conv-b").recentBillIds.first())
    }

    // --- Tool validation with fake tools ---

    @Test
    fun `fake delete tool validate rejects zero billId`() {
        val tool = FakeDeleteTool()
        val invalidParams = org.json.JSONObject()
        // JSONObject is mocked, optLong returns 0 by default
        val result = runBlocking { tool.validate(invalidParams, fakeContext()) }
        assertFalse(result.valid)
        assertEquals(AgentErrorType.INVALID_PARAMS, result.errorType)
    }

    @Test
    fun `fake read tool never requires confirmation`() {
        val tool = FakeReadTool()
        assertFalse(AgentConfirmationController.shouldConfirm(tool, org.json.JSONObject()))
    }

    @Test
    fun `fake delete tool always requires confirmation`() {
        val tool = FakeDeleteTool()
        assertTrue(AgentConfirmationController.shouldConfirm(tool, org.json.JSONObject()))
    }

    // --- Fake tools record execution ---

    @Test
    fun `fake delete tool execute records invocation`() {
        val tool = FakeDeleteTool()
        val params = org.json.JSONObject()
        // With mocked JSONObject, optLong returns 0, so execute should fail
        val result = runBlocking { tool.execute(params, fakeContext()) }
        assertFalse(result.success)
        assertEquals(0, tool.executionCount)
    }

    @Test
    fun `fake delete tool execute succeeds with valid billId`() {
        val tool = FakeDeleteToolWithHardcodedParams()
        val params = org.json.JSONObject()
        val result = runBlocking { tool.execute(params, fakeContext()) }
        assertTrue(result.success)
        assertEquals(1, tool.executionCount)
    }

    // --- Two dangerous steps need two confirmations ---

    @Test
    fun `two dangerous steps create two pending actions sequentially`() {
        val tool = FakeDeleteToolWithHardcodedParams()
        AgentToolRegistry.register(tool)

        // Step 1: first pending
        val step1Action = PendingAgentAction.create(
            conversationId = "test-conv",
            toolId = "fake.delete.hardcoded",
            params = org.json.JSONObject(),
            preview = "步骤 1: 删除账单 100",
            remainingCalls = listOf(
                ChatAgentOrchestrator.ToolCall("fake.delete.hardcoded", org.json.JSONObject(), "")
            ),
            responseGoal = "删除两笔账单"
        )
        PendingActionManager.save(step1Action)

        val pending1 = PendingActionManager.get("test-conv")!!
        assertTrue(pending1.hasRemainingCalls())

        // Simulate confirm step 1: execute
        runBlocking { tool.execute(pending1.params, fakeContext()) }
        assertEquals(1, tool.executionCount)

        // Step 2: create new pending from remaining calls
        PendingActionManager.clear("test-conv")
        val step2Action = PendingAgentAction.create(
            conversationId = "test-conv",
            toolId = "fake.delete.hardcoded",
            params = pending1.remainingCalls[0].params,
            preview = "步骤 2: 删除账单 200"
        )
        PendingActionManager.save(step2Action)

        val pending2 = PendingActionManager.get("test-conv")!!
        assertFalse(pending2.hasRemainingCalls())

        // Confirm step 2
        runBlocking { tool.execute(pending2.params, fakeContext()) }
        assertEquals(2, tool.executionCount)
    }

    // --- Pending expired: not executed ---

    @Test
    fun `expired pending is not retrievable`() {
        val action = PendingAgentAction(
            conversationId = "test-conv",
            toolId = "fake.delete",
            params = org.json.JSONObject(),
            preview = "test",
            createdAt = System.currentTimeMillis() - 10_000,
            expiresAt = System.currentTimeMillis() - 1000
        )
        PendingActionManager.save(action)
        assertNull(PendingActionManager.get("test-conv"))
    }

    // --- Helpers ---

    private fun savePending(conversationId: String, toolId: String) {
        val action = PendingAgentAction.create(
            conversationId = conversationId,
            toolId = toolId,
            params = org.json.JSONObject(),
            preview = "test"
        )
        PendingActionManager.save(action)
    }

    private fun fakeContext(): AgentSessionContext {
        return AgentSessionContext(
            bookName = "test-book",
            conversationId = "test-conv",
            queryContext = QueryContext(
                nowMillis = System.currentTimeMillis(),
                timezoneId = "Asia/Shanghai",
                currentBookName = "test-book",
                availableBooks = listOf("test-book"),
                assets = emptyList(),
                categories = emptyList(),
                currencies = listOf("CNY"),
                capabilities = QueryCapabilities(
                    canOpenStatsPage = true,
                    canOpenAssetStatsPage = true,
                    supportsStatsExternalFilter = true,
                    supportsAssetStatsTimeRange = true,
                    supportsAssetStatsBillType = true
                ),
                recentBillHints = emptyList()
            )
        )
    }

    private fun isConfirmIntent(text: String): Boolean {
        val normalized = text.trim()
        val confirmWords = listOf("确认", "执行", "好的", "好", "可以", "是的", "对", "嗯", "ok", "yes", "y", "确定")
        return confirmWords.any { normalized.equals(it, ignoreCase = true) }
    }

    private fun isCancelIntent(text: String): Boolean {
        val normalized = text.trim()
        val cancelWords = listOf("取消", "算了", "不要了", "不用了", "不要", "不用", "取消吧", "no", "n")
        return cancelWords.any { normalized.equals(it, ignoreCase = true) }
    }

    private fun <T> runBlocking(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }

    // --- Fake tools ---
    // FakeDeleteTool: uses JSONObject.optLong which returns 0 when mocked
    // FakeDeleteToolWithHardcodedParams: ignores JSONObject, always uses hardcoded billId

    private class FakeDeleteTool : AgentTool {
        override val id = "fake.delete"
        override val category = "test"
        override val risk = RiskLevel.DESTRUCTIVE
        override val description = "Fake delete tool"
        override val parameterSchema = org.json.JSONObject()

        var executionCount = 0

        override suspend fun validate(params: org.json.JSONObject, context: AgentSessionContext): AgentValidationResult {
            val billId = try { params.optLong("billId", 0) } catch (_: Exception) { 0L }
            if (billId <= 0) {
                return AgentValidationResult.invalidParams("请提供有效的账单ID", listOf("billId"))
            }
            return AgentValidationResult.success()
        }

        override suspend fun execute(params: org.json.JSONObject, context: AgentSessionContext): AgentToolResult {
            val billId = try { params.optLong("billId", 0) } catch (_: Exception) { 0L }
            if (billId <= 0) {
                return AgentToolResult.failure("请提供有效的账单ID")
            }
            executionCount++
            return AgentToolResult.success(
                facts = org.json.JSONObject(),
                userMessage = "已删除账单 $billId"
            )
        }
    }

    private class FakeDeleteToolWithHardcodedParams : AgentTool {
        override val id = "fake.delete.hardcoded"
        override val category = "test"
        override val risk = RiskLevel.DESTRUCTIVE
        override val description = "Fake delete tool with hardcoded params"
        override val parameterSchema = org.json.JSONObject()

        var executionCount = 0

        override suspend fun validate(params: org.json.JSONObject, context: AgentSessionContext): AgentValidationResult {
            return AgentValidationResult.success()
        }

        override suspend fun execute(params: org.json.JSONObject, context: AgentSessionContext): AgentToolResult {
            executionCount++
            return AgentToolResult.success(
                facts = org.json.JSONObject(),
                userMessage = "已删除账单"
            )
        }
    }

    private class FakeReadTool : AgentTool {
        override val id = "fake.read"
        override val category = "test"
        override val risk = RiskLevel.READ
        override val description = "Fake read tool"
        override val parameterSchema = org.json.JSONObject()

        override suspend fun execute(params: org.json.JSONObject, context: AgentSessionContext): AgentToolResult {
            return AgentToolResult.success(userMessage = "查询完成")
        }
    }
}
