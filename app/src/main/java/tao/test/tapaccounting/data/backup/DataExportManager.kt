package tao.test.tapaccounting.data.backup

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import tao.test.tapaccounting.data.local.entity.*

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
}



