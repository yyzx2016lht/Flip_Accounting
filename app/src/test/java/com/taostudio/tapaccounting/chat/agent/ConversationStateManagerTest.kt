package com.taostudio.tapaccounting.chat.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConversationStateManagerTest {

    @Before
    fun setUp() {
        ConversationStateManager.clearAll()
    }

    @Test
    fun `get state returns default for new conversation`() {
        val state = ConversationStateManager.getState("conv1")
        assertNotNull(state)
        assertTrue(state.activeSkillIds.isEmpty())
        assertNull(state.lastToolId)
        assertTrue(state.recentBillIds.isEmpty())
    }

    @Test
    fun `update state persists changes`() {
        ConversationStateManager.updateState("conv2") { state ->
            state.withLastTool("bill.delete").withRecentBill(123)
        }
        val state = ConversationStateManager.getState("conv2")
        assertEquals("bill.delete", state.lastToolId)
        assertEquals(listOf(123L), state.recentBillIds)
    }

    @Test
    fun `recent bill ids are limited`() {
        ConversationStateManager.updateState("conv3") { state ->
            var updated = state
            for (i in 1..20) {
                updated = updated.withRecentBill(i.toLong())
            }
            updated
        }
        val state = ConversationStateManager.getState("conv3")
        assertTrue(state.recentBillIds.size <= 10)
        assertEquals(20L, state.recentBillIds.first())
    }

    @Test
    fun `clear state removes conversation`() {
        ConversationStateManager.updateState("conv4") { it.withLastTool("test") }
        ConversationStateManager.clearState("conv4")
        val state = ConversationStateManager.getState("conv4")
        assertNull(state.lastToolId)
    }

    @Test
    fun `state isolation between conversations`() {
        ConversationStateManager.updateState("conv5") { it.withLastTool("tool1") }
        ConversationStateManager.updateState("conv6") { it.withLastTool("tool2") }
        assertEquals("tool1", ConversationStateManager.getState("conv5").lastToolId)
        assertEquals("tool2", ConversationStateManager.getState("conv6").lastToolId)
    }
}
