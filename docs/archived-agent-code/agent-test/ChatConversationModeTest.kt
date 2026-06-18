package com.taostudio.tapaccounting.chat.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConversationModeTest {

    @Test
    fun `agent mode creates agent conversation id`() {
        val id = ChatConversationMode.createId(ChatConversationMode.AGENT)

        assertTrue(id.startsWith("agent_"))
        assertEquals(ChatConversationMode.AGENT, ChatConversationMode.modeOf(id))
    }

    @Test
    fun `accounting mode creates accounting conversation id`() {
        val id = ChatConversationMode.createId(ChatConversationMode.ACCOUNTING)

        assertTrue(id.startsWith("conv_"))
        assertEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.modeOf(id))
    }

    @Test
    fun `legacy conversation defaults to accounting`() {
        assertEquals(
            ChatConversationMode.ACCOUNTING,
            ChatConversationMode.modeOf("legacy")
        )
    }
}
