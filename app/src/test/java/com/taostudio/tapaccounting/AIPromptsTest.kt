package com.taostudio.tapaccounting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIPromptsTest {
    @Test
    fun intentRouterPromptContainsKeyElements() {
        val prompt = AIPrompts.INTENT_ROUTER_PROMPT_DEFAULT

        assertTrue(prompt.contains("BOOKKEEPING"))
        assertTrue(prompt.contains("GENERAL_CHAT"))
        assertTrue(prompt.contains("intent"))
    }
}

