package com.taostudio.tapaccounting.logic

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs

/** 双人共享账本的可选个人预算拆分；总预算仍以 Budget.amount 为准。 */
object MemberBudgetAllocation {
    private const val SUM_TOLERANCE = 0.01

    fun complete(
        totalBudget: Double,
        firstMemberId: String,
        firstAmount: Double?,
        secondMemberId: String,
        secondAmount: Double?
    ): Map<String, Double>? {
        require(totalBudget.isFinite() && totalBudget > 0.0)
        if (firstAmount == null && secondAmount == null) return null

        val first = firstAmount ?: (totalBudget - requireNotNull(secondAmount))
        val second = secondAmount ?: (totalBudget - first)
        require(first.isFinite() && second.isFinite() && first >= 0.0 && second >= 0.0)
        require(abs(first + second - totalBudget) <= SUM_TOLERANCE)
        return linkedMapOf(firstMemberId to first, secondMemberId to second)
    }

    fun encode(allocations: Map<String, Double>?): String? {
        if (allocations.isNullOrEmpty()) return null
        return JsonObject().apply {
            allocations.toSortedMap().forEach { (memberId, amount) ->
                if (memberId.isNotBlank() && amount.isFinite() && amount >= 0.0) addProperty(memberId, amount)
            }
        }.takeIf { it.size() > 0 }?.toString()
    }

    fun decode(raw: String?): Map<String, Double> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            JsonParser.parseString(raw).asJsonObject.entrySet().mapNotNull { (memberId, value) ->
                val amount = value.takeIf { it.isJsonPrimitive }?.asDouble
                if (memberId.isBlank() || amount == null || !amount.isFinite() || amount < 0.0) null
                else memberId to amount
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun amountFor(totalBudget: Double, encodedAllocations: String?, memberId: String): Double =
        decode(encodedAllocations)[memberId] ?: totalBudget
}
