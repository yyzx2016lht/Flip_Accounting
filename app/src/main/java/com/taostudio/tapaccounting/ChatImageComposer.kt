package com.taostudio.tapaccounting

/**
 * Pure-logic helper for the multi-image pending composer.
 *
 * Every function here is side-effect-free and Android-free, making it
 * straightforward to unit-test without Robolectric.
 */
object ChatImageComposer {

    const val MAX_PENDING_IMAGES = 9

    // ---- limit ---------------------------------------------------------------

    /**
     * Returns `true` when the list has room for one more image.
     */
    fun canAddImage(currentCount: Int): Boolean = currentCount < MAX_PENDING_IMAGES

    /**
     * Returns `true` when [currentCount] has already reached the limit.
     */
    fun isAtLimit(currentCount: Int): Boolean = currentCount >= MAX_PENDING_IMAGES

    // ---- multi-image accounting payload --------------------------------------

    /**
     * Encode multiple images + supplement text into a single payload string
     * understood by [ChatMessagePipeline].
     *
     * Format:
     * ```
     * [MULTIMODAL_MULTI]<count>|<base64_1>|<mime_1>|<base64_2>|<mime_2>|…|<supplement>
     * ```
     *
     * For single-image payloads the legacy [ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX]
     * format is still produced so existing pipeline paths keep working.
     */
    fun encodeMultiImagePayload(
        images: List<PendingImage>,
        supplement: String,
        useDraftPrefix: Boolean
    ): String {
        require(images.isNotEmpty()) { "images must not be empty" }
        if (images.size == 1) {
            val img = images.first()
            val prefix = if (useDraftPrefix) {
                ReceiptImageInputHelper.MULTIMODAL_PREFIX
            } else {
                ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX
            }
            return ReceiptImageInputHelper.encodePayload(prefix, img.base64, img.mime, supplement)
        }
        // Multi-image: use the dedicated multi prefix
        val safeSupplement = supplement.replace("|", " ").trim()
        return buildString {
            append(MULTIMODAL_MULTI_PREFIX)
            append(images.size)
            for (img in images) {
                append("|")
                append(img.base64)
                append("|")
                append(img.mime)
            }
            append("|")
            append(safeSupplement)
        }
    }

    // ---- decode multi-image payload ------------------------------------------

    /**
     * Decode a payload produced by [encodeMultiImagePayload].
     *
     * Returns a list of [ReceiptImageInputHelper.ImagePayload] (one per image)
     * and the shared supplement text, or `null` if the payload is not valid.
     */
    fun decodeMultiImagePayload(raw: String): MultiImagePayload? {
        if (!raw.startsWith(MULTIMODAL_MULTI_PREFIX)) return null
        val body = raw.removePrefix(MULTIMODAL_MULTI_PREFIX)
        val parts = body.split("|")
        if (parts.size < 2) return null
        val count = parts[0].toIntOrNull() ?: return null
        if (count <= 0) return null
        // Each image = base64 + mime = 2 parts, then 1 supplement at the end
        val expectedParts = count * 2 + 1
        if (parts.size < expectedParts) return null

        val images = mutableListOf<ReceiptImageInputHelper.ImagePayload>()
        for (i in 0 until count) {
            val base64 = parts[1 + i * 2]
            val mime = parts[2 + i * 2]
            if (base64.isBlank()) return null
            images.add(ReceiptImageInputHelper.ImagePayload(base64, mime, ""))
        }
        val supplement = parts.getOrElse(1 + count * 2) { "" }.trim()
        return MultiImagePayload(images, supplement)
    }

    /**
     * Returns `true` when [raw] is a multi-image payload.
     */
    fun isMultiImagePayload(raw: String): Boolean = raw.startsWith(MULTIMODAL_MULTI_PREFIX)

    // ---- agent multi-image context -------------------------------------------

    /**
     * Format the combined context text the Agent receives when the user sends
     * multiple images.  Each image's OCR result is labelled so the Agent can
     * distinguish them.
     */
    fun formatAgentMultiImageContext(ocrResults: List<String>, userText: String): String = buildString {
        append("[用户发送了${ocrResults.size}张图片]")
        ocrResults.forEachIndexed { index, ocr ->
            append("\n图片${index + 1}内容：")
            append(ocr.ifBlank { "（未识别到内容）" })
        }
        if (userText.isNotBlank()) {
            append("\n用户说：")
            append(userText)
        }
    }

    /**
     * Convenience overload for a single image that preserves backward compat
     * with the old "[用户发送了一张图片]" format.
     */
    fun formatAgentSingleImageContext(ocrText: String, userText: String): String = buildString {
        append("[用户发送了一张图片]")
        if (ocrText.isNotBlank()) {
            append("\n图片内容：")
            append(ocrText)
        }
        if (userText.isNotBlank()) {
            append("\n用户说：")
            append(userText)
        }
    }

    /**
     * Route to the right formatter based on image count.
     */
    fun formatAgentImageContext(ocrResults: List<String>, userText: String): String {
        return if (ocrResults.size <= 1) {
            formatAgentSingleImageContext(ocrResults.firstOrNull().orEmpty(), userText)
        } else {
            formatAgentMultiImageContext(ocrResults, userText)
        }
    }

    // ---- remove by index (returns new list) ----------------------------------

    /**
     * Return a new list with the element at [index] removed.
     * If [index] is out of range the original list is returned unchanged.
     */
    fun removeAt(images: List<PendingImage>, index: Int): List<PendingImage> {
        if (index !in images.indices) return images
        return images.toMutableList().apply { removeAt(index) }
    }

    // ---- constants -----------------------------------------------------------

    const val MULTIMODAL_MULTI_PREFIX = "[MULTIMODAL_MULTI]"
}

/**
 * Result of decoding a multi-image payload.
 */
data class MultiImagePayload(
    val images: List<ReceiptImageInputHelper.ImagePayload>,
    val supplement: String
)
