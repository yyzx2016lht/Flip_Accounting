package com.taostudio.tapaccounting.logic

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs

/** 共享账本的可选个人预算拆分；总预算仍以 Budget.amount 为准。 */
object MemberBudgetAllocation {
    private const val SUM_TOLERANCE = 0.01

    fun complete(
        totalBudget: Double,
        firstMemberId: String,
        firstAmount: Double?,
        secondMemberId: String,
        secondAmount: Double?
    ): Map<String, Double>? = complete(
        totalBudget = totalBudget,
        memberAmounts = linkedMapOf(
            firstMemberId to firstAmount,
            secondMemberId to secondAmount
        )
    )

    /**
     * Completes a 2-5 member allocation. Blank members share the unassigned
     * remainder equally; leaving every member blank keeps the legacy shared
     * total-budget behaviour.
     */
    fun complete(
        totalBudget: Double,
        memberAmounts: Map<String, Double?>
    ): Map<String, Double>? {
        require(totalBudget.isFinite() && totalBudget > 0.0)
        require(memberAmounts.size in 2..5)
        require(memberAmounts.keys.none { it.isBlank() })
        if (memberAmounts.values.all { it == null }) return null

        val explicitTotal = memberAmounts.values.filterNotNull().sumOf { amount ->
            require(amount.isFinite() && amount >= 0.0)
            amount
        }
        val blankMemberIds = memberAmounts.filterValues { it == null }.keys.toList()
        val remainder = totalBudget - explicitTotal
        require(remainder >= -SUM_TOLERANCE)

        if (blankMemberIds.isEmpty()) {
            require(abs(remainder) <= SUM_TOLERANCE)
            return LinkedHashMap(memberAmounts.mapValues { requireNotNull(it.value) })
        }

        val safeRemainder = remainder.coerceAtLeast(0.0)
        val equalShare = safeRemainder / blankMemberIds.size
        val result = linkedMapOf<String, Double>()
        var assignedBlankTotal = 0.0
        memberAmounts.forEach { (memberId, amount) ->
            result[memberId] = amount ?: if (memberId == blankMemberIds.last()) {
                safeRemainder - assignedBlankTotal
            } else {
                equalShare.also { assignedBlankTotal += it }
            }
        }
        require(result.values.all { it.isFinite() && it >= 0.0 })
        require(abs(result.values.sum() - totalBudget) <= SUM_TOLERANCE)
        return result
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
