package com.taostudio.tapaccounting.data.sync

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.taostudio.tapaccounting.data.local.entity.Budget

/** Shared-budget wire format, kept in one place so new budget fields cannot be dropped by a caller. */
object SharedBudgetPayloadCodec {
    private val gson = Gson()

    fun encode(budget: Budget): JsonObject = gson.toJsonTree(budget.copy(id = 0)).asJsonObject

    fun decode(payload: JsonElement?): Budget? =
        runCatching { gson.fromJson(payload, Budget::class.java) }.getOrNull()
}
