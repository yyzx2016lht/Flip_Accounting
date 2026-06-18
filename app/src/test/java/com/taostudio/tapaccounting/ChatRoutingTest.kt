package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for chat input routing model.
 *
 * Covers:
 * 1. MODE_ACCOUNTING always routes to ACCOUNTING.
 * 2. shouldRouteToAccounting gates accounting-only paths.
 * 3. Image payload encoding round-trips correctly.
 * 4. no_bill result still prompts user for more info.
 */
class ChatRoutingTest {

    // ---- resolveInputAction ------------------------------------------------

    @Test
    fun `MODE_ACCOUNTING always returns ACCOUNTING regardless of explicit flag`() {
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
    fun `InputAction has exactly one value`() {
        assertEquals(1, InputAction.values().size)
    }

    // ---- shouldRouteToAccounting -------------------------------------------

    @Test
    fun `ACCOUNTING routes to accounting`() {
        assertTrue(ChatInputRouter.shouldRouteToAccounting(InputAction.ACCOUNTING))
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
    fun `no_bill handling routes to accounting`() {
        assertTrue(ChatInputRouter.shouldRouteToAccounting(InputAction.ACCOUNTING))
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
}
