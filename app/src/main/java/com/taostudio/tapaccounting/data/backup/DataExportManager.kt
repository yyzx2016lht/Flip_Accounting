package com.taostudio.tapaccounting.data.backup

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.taostudio.tapaccounting.data.local.entity.*

object DataExportManager {
    private val gson = Gson()

    fun serialize(data: Any): String = gson.toJson(data)

    fun deserializeAssets(json: String): List<Asset> = gson.fromJson(json, object : TypeToken<List<Asset>>() {}.type)
    fun deserializeBills(json: String): List<Bill> = gson.fromJson(json, object : TypeToken<List<Bill>>() {}.type)
    fun deserializeDeletedBills(json: String): List<DeletedBill> = gson.fromJson(json, object : TypeToken<List<DeletedBill>>() {}.type)
    fun deserializeInvestmentLots(json: String): List<InvestmentLot> = gson.fromJson(json, object : TypeToken<List<InvestmentLot>>() {}.type)
    fun deserializeCategories(json: String): List<Category> = gson.fromJson(json, object : TypeToken<List<Category>>() {}.type)
    fun deserializeAiRules(json: String): List<AiRule> = gson.fromJson(json, object : TypeToken<List<AiRule>>() {}.type)
    fun deserializeChatMessages(json: String): List<ChatMessage> =
        gson.fromJson(json, object : TypeToken<List<ChatMessage>>() {}.type)
    fun deserializeBudgets(json: String): List<Budget> =
        gson.fromJson(json, object : TypeToken<List<Budget>>() {}.type)
    fun deserializeRecurringPatterns(json: String): List<RecurringPattern> =
        gson.fromJson(json, object : TypeToken<List<RecurringPattern>>() {}.type)
}




