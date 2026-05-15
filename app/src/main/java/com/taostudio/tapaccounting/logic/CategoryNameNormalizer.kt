package com.taostudio.tapaccounting.logic

object CategoryNameNormalizer {

    private const val REFUND_PREFIX = "\u9000\u6b3e\uff1a"
    private const val REFUND_PREFIX_ALT = "\u9000\u6b3e\u00b7"
    private const val UNIFIED_CHILD_SEPARATOR = " - "
    private val childSeparators = Regex("\\s*(/:::/|/::/|>|/|\\\\|\\||::|:|·)\\s*")

    fun normalizeForStorage(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""

        val hasRefundPrefix = text.startsWith(REFUND_PREFIX) || text.startsWith(REFUND_PREFIX_ALT)
        val base = stripRefundPrefix(text)

        val normalizedBase = base
            .split(childSeparators)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(UNIFIED_CHILD_SEPARATOR)

        if (normalizedBase.isBlank()) return ""
        return if (hasRefundPrefix) "$REFUND_PREFIX$normalizedBase" else normalizedBase
    }

    fun stripRefundPrefix(categoryName: String): String {
        var normalized = categoryName.trim()
        while (true) {
            normalized = when {
                normalized.startsWith(REFUND_PREFIX) -> normalized.removePrefix(REFUND_PREFIX).trim()
                normalized.startsWith(REFUND_PREFIX_ALT) -> normalized.removePrefix(REFUND_PREFIX_ALT).trim()
                else -> break
            }
        }
        return normalized
    }
}

