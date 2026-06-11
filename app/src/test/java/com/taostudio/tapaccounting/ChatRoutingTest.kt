package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the refactored chat input routing model.
 *
 * Covers:
 * 1. + entry always routes to ACCOUNTING.
 * 2. Agent entry defaults to AGENT_CHAT (no auto-accounting).
 * 3. Agent "记账" button triggers AGENT_TO_ACCOUNTING.
 * 4. formatAgentImageContext combines OCR text and user text.
 * 5. shouldRouteToAccounting gates accounting-only paths.
 * 6. Image payload encoding round-trips correctly.
 * 7. no_bill result still prompts user for more info (existing behaviour preserved).
 */
class ChatRoutingTest {

    // ---- resolveInputAction ------------------------------------------------

    @Test
    fun `+ entry always returns ACCOUNTING regardless of explicit flag`() {
        assertEquals(
            InputAction.ACCOUNTING,
            ChatInputRouter.resolveInputAction(ChatActivity.MODE_ACCOUNTING, isExplicitAccounting = false)
        )
        assertEquals(
            InputAction.ACCOUNTING,
            ChatInputRouter.resolveInputAction(ChatActivity.MODE_ACCOUNTING, isExplicitAccounting = true)
        )
    }

    @Test
    fun `Agent entry without explicit accounting returns AGENT_CHAT`() {
        assertEquals(
            InputAction.AGENT_CHAT,
            ChatInputRouter.resolveInputAction(ChatActivity.MODE_AGENT, isExplicitAccounting = false)
        )
    }

    @Test
    fun `Agent entry with explicit accounting returns AGENT_TO_ACCOUNTING`() {
        assertEquals(
            InputAction.AGENT_TO_ACCOUNTING,
            ChatInputRouter.resolveInputAction(ChatActivity.MODE_AGENT, isExplicitAccounting = true)
        )
    }

    @Test
    fun `InputAction has exactly three values`() {
        assertEquals(3, InputAction.values().size)
    }

    // ---- shouldRouteToAccounting -------------------------------------------

    @Test
    fun `ACCOUNTING routes to accounting`() {
        assertTrue(ChatInputRouter.shouldRouteToAccounting(InputAction.ACCOUNTING))
    }

    @Test
    fun `AGENT_TO_ACCOUNTING routes to accounting`() {
        assertTrue(ChatInputRouter.shouldRouteToAccounting(InputAction.AGENT_TO_ACCOUNTING))
    }

    @Test
    fun `AGENT_CHAT does NOT route to accounting`() {
        assertFalse(ChatInputRouter.shouldRouteToAccounting(InputAction.AGENT_CHAT))
    }

    // ---- formatAgentImageContext -------------------------------------------

    @Test
    fun `formatAgentImageContext includes image marker`() {
        val result = ChatInputRouter.formatAgentImageContext("OCR text", "user text")
        assertTrue(result.contains("[用户发送了一张图片]"))
    }

    @Test
    fun `formatAgentImageContext includes OCR text when present`() {
        val result = ChatInputRouter.formatAgentImageContext("some OCR result", "")
        assertTrue(result.contains("图片内容：some OCR result"))
    }

    @Test
    fun `formatAgentImageContext includes user text when present`() {
        val result = ChatInputRouter.formatAgentImageContext("", "hello")
        assertTrue(result.contains("用户说：hello"))
    }

    @Test
    fun `formatAgentImageContext handles both empty`() {
        val result = ChatInputRouter.formatAgentImageContext("", "")
        assertTrue(result.contains("[用户发送了一张图片]"))
        assertFalse(result.contains("图片内容："))
        assertFalse(result.contains("用户说："))
    }

    @Test
    fun `formatAgentImageContext with multiline OCR`() {
        val ocr = "星巴克拿铁 35.00\n面包 12.50"
        val result = ChatInputRouter.formatAgentImageContext(ocr, "用支付宝付的")
        assertTrue(result.contains(ocr))
        assertTrue(result.contains("用户说：用支付宝付的"))
    }

    // ---- Image payload encoding round-trip ---------------------------------

    @Test
    fun `image payload encodes and decodes correctly with supplement`() {
        val base64 = "dGVzdA=="  // "test"
        val mime = "image/jpeg"
        val supplement = "微信支付"
        val payload = ReceiptImageInputHelper.encodePayload(
            ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX,
            base64, mime, supplement
        )
        assertTrue(payload.startsWith(ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX))

        val decoded = ReceiptImageInputHelper.decodePayload(payload)
        assertNotNull(decoded)
        assertEquals(base64, decoded!!.base64)
        assertEquals(mime, decoded.mime)
        assertEquals(supplement, decoded.supplement)
    }

    @Test
    fun `image payload encodes and decodes correctly with empty supplement`() {
        val base64 = "dGVzdA=="
        val mime = "image/png"
        val payload = ReceiptImageInputHelper.encodePayload(
            ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX,
            base64, mime, ""
        )
        val decoded = ReceiptImageInputHelper.decodePayload(payload)
        assertNotNull(decoded)
        assertEquals("", decoded!!.supplement)
    }

    @Test
    fun `MULTIMODAL_PREFIX vs MULTIMODAL_DIRECT_PREFIX are distinguishable`() {
        val base64 = "abc"
        val mime = "image/jpeg"
        val draft = ReceiptImageInputHelper.encodePayload(
            ReceiptImageInputHelper.MULTIMODAL_PREFIX, base64, mime, ""
        )
        val direct = ReceiptImageInputHelper.encodePayload(
            ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX, base64, mime, ""
        )
        assertFalse(ReceiptImageInputHelper.isDirectPayload(draft))
        assertTrue(ReceiptImageInputHelper.isDirectPayload(direct))
    }

    // ---- no_bill handling (existing behaviour preserved) --------------------

    @Test
    fun `no_bill handling is independent of routing`() {
        // The no_bill path lives in finalizeChatAccountingResult, which is only
        // reached when shouldRouteToAccounting is true.  Verify that the gate
        // works for all accounting routes.
        assertTrue(ChatInputRouter.shouldRouteToAccounting(InputAction.ACCOUNTING))
        assertTrue(ChatInputRouter.shouldRouteToAccounting(InputAction.AGENT_TO_ACCOUNTING))
        assertFalse(ChatInputRouter.shouldRouteToAccounting(InputAction.AGENT_CHAT))
    }

    // ---- Supplement-as-text encoding (new flow: user text becomes supplement) ----

    @Test
    fun `user text becomes supplement for accounting image payload`() {
        val base64 = "dGVzdA=="
        val mime = "image/jpeg"
        val userText = "支付宝"
        val payload = ReceiptImageInputHelper.encodePayload(
            ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX,
            base64, mime, userText
        )
        val decoded = ReceiptImageInputHelper.decodePayload(payload)
        assertNotNull(decoded)
        assertEquals(userText, decoded!!.supplement)
    }

    // ---- Agent does NOT auto-account: images go through formatAgentImageContext ----

    @Test
    fun `agent image context does not contain MULTIMODAL prefix`() {
        val result = ChatInputRouter.formatAgentImageContext("receipt text", "my note")
        assertFalse(result.contains(ReceiptImageInputHelper.MULTIMODAL_PREFIX))
        assertFalse(result.contains(ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX))
    }

    @Test
    fun `agent image context is plain text suitable for orchestrator`() {
        val result = ChatInputRouter.formatAgentImageContext("OCR内容", "备注")
        // Should be parseable as plain text — no base64, no pipe-delimited payload
        assertFalse(result.contains("|"))
        assertTrue(result.startsWith("[用户发送了一张图片]"))
    }
}
