package com.taostudio.tapaccounting.data.sync.protocol

import com.google.gson.JsonElement
import java.math.BigDecimal

/**
 * Shared strict JSON number parsing for protocol fields.
 *
 * Gson's `asInt`/`asLong` silently truncate fractional parts and overflow
 * on out-of-range values. This utility rejects non-integer numbers,
 * values outside the target type's range, and NaN/Infinity.
 */
object StrictJsonParser {

    /**
     * Parses a JSON element as a strict Int.
     *
     * - Must be a JSON number (not string, boolean, null, object, array)
     * - Must have no fractional part (rejects 1.5, 2.7, etc.)
     * - Must be within [Int.MIN_VALUE, Int.MAX_VALUE]
     * - Rejects NaN, Infinity
     *
     * @return The parsed Int, or null if the element is not a JSON primitive or not a number.
     * @throws IllegalStateException if the value is not an integer or is out of Int range.
     */
    fun parseInt(element: JsonElement?, name: String): Int? {
        if (element == null || !element.isJsonPrimitive) return null
        val prim = element.asJsonPrimitive
        if (!prim.isNumber) return null
        val longVal = parseLongOrThrow(prim.asNumber, name)
        if (longVal !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw IllegalStateException(
                "$name out of Int range: $longVal (must be ${Int.MIN_VALUE}..${Int.MAX_VALUE})"
            )
        }
        return longVal.toInt()
    }

    /**
     * Parses a JSON element as a strict Long.
     *
     * - Must be a JSON number
     * - Must have no fractional part
     * - Must be within [Long.MIN_VALUE, Long.MAX_VALUE]
     * - Rejects NaN, Infinity
     *
     * @return The parsed Long, or null if the element is not a JSON primitive or not a number.
     * @throws IllegalStateException if the value is not an integer or is out of Long range.
     */
    fun parseLong(element: JsonElement?, name: String): Long? {
        if (element == null || !element.isJsonPrimitive) return null
        val prim = element.asJsonPrimitive
        if (!prim.isNumber) return null
        return parseLongOrThrow(prim.asNumber, name)
    }

    /**
     * Core parsing: converts a [Number] to Long with strict integer validation.
     *
     * Uses [BigDecimal] for precise range and fraction checking, avoiding
     * double-precision rounding errors for large values.
     */
    private fun parseLongOrThrow(num: Number, name: String): Long {
        val bd = BigDecimal(num.toString())
        if (bd.stripTrailingZeros().scale() > 0) {
            throw IllegalStateException(
                "$name must be an integer, got: ${num.toString()}"
            )
        }
        if (bd < BigDecimal(Long.MIN_VALUE) || bd > BigDecimal(Long.MAX_VALUE)) {
            throw IllegalStateException(
                "$name out of Long range: ${num.toString()}"
            )
        }
        return bd.longValueExact()
    }
}
