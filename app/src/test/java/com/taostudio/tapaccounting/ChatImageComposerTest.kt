package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ChatImageComposer] — the pure-logic multi-image composer.
 *
 * Covers:
 *  1. Pending image limit is 9.
 *  2. Adding beyond limit is rejected.
 *  3. Multi-image payload encode/decode round-trip.
 *  4. Single-image payload falls back to legacy format.
 *  5. removeAt produces correct list.
 *  6. Edge cases: empty list, out-of-range index, etc.
 */
class ChatImageComposerTest {

    // ---- limit ---------------------------------------------------------------

    @Test
    fun `MAX_PENDING_IMAGES is 9`() {
        assertEquals(9, ChatImageComposer.MAX_PENDING_IMAGES)
    }

    @Test
    fun `canAddImage returns true when below limit`() {
        assertTrue(ChatImageComposer.canAddImage(0))
        assertTrue(ChatImageComposer.canAddImage(5))
        assertTrue(ChatImageComposer.canAddImage(8))
    }

    @Test
    fun `canAddImage returns false when at limit`() {
        assertFalse(ChatImageComposer.canAddImage(9))
        assertFalse(ChatImageComposer.canAddImage(10))
    }

    @Test
    fun `isAtLimit returns true when at or above limit`() {
        assertTrue(ChatImageComposer.isAtLimit(9))
        assertTrue(ChatImageComposer.isAtLimit(10))
    }

    @Test
    fun `isAtLimit returns false when below limit`() {
        assertFalse(ChatImageComposer.isAtLimit(0))
        assertFalse(ChatImageComposer.isAtLimit(8))
    }

    // ---- removeAt ------------------------------------------------------------

    @Test
    fun `removeAt returns list without element at index`() {
        val a = makeImage("a")
        val b = makeImage("b")
        val c = makeImage("c")
        val result = ChatImageComposer.removeAt(listOf(a, b, c), 1)
        assertEquals(2, result.size)
        assertEquals("a", result[0].base64)
        assertEquals("c", result[1].base64)
    }

    @Test
    fun `removeAt first element`() {
        val a = makeImage("a")
        val b = makeImage("b")
        val result = ChatImageComposer.removeAt(listOf(a, b), 0)
        assertEquals(1, result.size)
        assertEquals("b", result[0].base64)
    }

    @Test
    fun `removeAt last element`() {
        val a = makeImage("a")
        val b = makeImage("b")
        val result = ChatImageComposer.removeAt(listOf(a, b), 1)
        assertEquals(1, result.size)
        assertEquals("a", result[0].base64)
    }

    @Test
    fun `removeAt out of range returns original list`() {
        val a = makeImage("a")
        val original = listOf(a)
        val result = ChatImageComposer.removeAt(original, 5)
        assertEquals(1, result.size)
        assertEquals("a", result[0].base64)
    }

    @Test
    fun `removeAt negative index returns original list`() {
        val a = makeImage("a")
        val result = ChatImageComposer.removeAt(listOf(a), -1)
        assertEquals(1, result.size)
    }

    // ---- multi-image payload encode/decode -----------------------------------

    @Test
    fun `encodeMultiImagePayload single image uses legacy format`() {
        val img = makeImage("abc")
        val payload = ChatImageComposer.encodeMultiImagePayload(listOf(img), "supplement", false)
        assertTrue(payload.startsWith(ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX))
        assertFalse(ChatImageComposer.isMultiImagePayload(payload))
        // Should decode via legacy decoder
        val decoded = ReceiptImageInputHelper.decodePayload(payload)
        assertNotNull(decoded)
        assertEquals("abc", decoded!!.base64)
        assertEquals("supplement", decoded.supplement)
    }

    @Test
    fun `encodeMultiImagePayload single image draft prefix`() {
        val img = makeImage("abc")
        val payload = ChatImageComposer.encodeMultiImagePayload(listOf(img), "supplement", true)
        assertTrue(payload.startsWith(ReceiptImageInputHelper.MULTIMODAL_PREFIX))
        assertFalse(payload.startsWith(ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX))
    }

    @Test
    fun `encodeMultiImagePayload multi image uses multi prefix`() {
        val images = listOf(makeImage("a"), makeImage("b"), makeImage("c"))
        val payload = ChatImageComposer.encodeMultiImagePayload(images, "supplement", false)
        assertTrue(payload.startsWith(ChatImageComposer.MULTIMODAL_MULTI_PREFIX))
        assertTrue(ChatImageComposer.isMultiImagePayload(payload))
    }

    @Test
    fun `encodeMultiImagePayload multi image decode round trip`() {
        val images = listOf(
            makeImage("base64_1", "image/jpeg"),
            makeImage("base64_2", "image/png"),
            makeImage("base64_3", "image/webp")
        )
        val payload = ChatImageComposer.encodeMultiImagePayload(images, "支付宝", false)
        val decoded = ChatImageComposer.decodeMultiImagePayload(payload)
        assertNotNull(decoded)
        assertEquals(3, decoded!!.images.size)
        assertEquals("base64_1", decoded.images[0].base64)
        assertEquals("image/jpeg", decoded.images[0].mime)
        assertEquals("base64_2", decoded.images[1].base64)
        assertEquals("image/png", decoded.images[1].mime)
        assertEquals("base64_3", decoded.images[2].base64)
        assertEquals("image/webp", decoded.images[2].mime)
        assertEquals("支付宝", decoded.supplement)
    }

    @Test
    fun `encodeMultiImagePayload multi image empty supplement`() {
        val images = listOf(makeImage("a"), makeImage("b"))
        val payload = ChatImageComposer.encodeMultiImagePayload(images, "", false)
        val decoded = ChatImageComposer.decodeMultiImagePayload(payload)
        assertNotNull(decoded)
        assertEquals("", decoded!!.supplement)
    }

    @Test
    fun `encodeMultiImagePayload supplement pipe is sanitised`() {
        val images = listOf(makeImage("a"))
        val payload = ChatImageComposer.encodeMultiImagePayload(images, "a|b|c", false)
        // Single image uses legacy format; the supplement pipe is sanitised there too
        val decoded = ReceiptImageInputHelper.decodePayload(payload)
        assertNotNull(decoded)
        assertFalse(decoded!!.supplement.contains("|"))
    }

    @Test
    fun `decodeMultiImagePayload returns null for non-multi payload`() {
        assertNull(ChatImageComposer.decodeMultiImagePayload("not a payload"))
        assertNull(ChatImageComposer.decodeMultiImagePayload(ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX + "abc|mime|"))
    }

    @Test
    fun `decodeMultiImagePayload returns null for malformed payload`() {
        // Missing count
        assertNull(ChatImageComposer.decodeMultiImagePayload(ChatImageComposer.MULTIMODAL_MULTI_PREFIX))
        // Count=2 but only 1 image worth of parts
        assertNull(ChatImageComposer.decodeMultiImagePayload(ChatImageComposer.MULTIMODAL_MULTI_PREFIX + "2|abc|mime|"))
    }

    @Test
    fun `isMultiImagePayload correct for various inputs`() {
        assertTrue(ChatImageComposer.isMultiImagePayload(ChatImageComposer.MULTIMODAL_MULTI_PREFIX + "1|abc|mime|"))
        assertFalse(ChatImageComposer.isMultiImagePayload("random text"))
        assertFalse(ChatImageComposer.isMultiImagePayload(ReceiptImageInputHelper.MULTIMODAL_PREFIX + "abc|mime|"))
    }

    // ---- multi-image payload preserves all images (not just first) -----------

    @Test
    fun `multi-image payload contains all images not just first`() {
        val images = (1..9).map { makeImage("img_$it", "image/jpeg") }
        val payload = ChatImageComposer.encodeMultiImagePayload(images, "text", false)
        val decoded = ChatImageComposer.decodeMultiImagePayload(payload)
        assertNotNull(decoded)
        assertEquals(9, decoded!!.images.size)
        for (i in 1..9) {
            assertEquals("img_$i", decoded.images[i - 1].base64)
        }
    }

    // ---- helpers -------------------------------------------------------------

    private fun makeImage(base64: String, mime: String = "image/jpeg") =
        PendingImage(uri = android.net.Uri.EMPTY, base64 = base64, mime = mime)
}
