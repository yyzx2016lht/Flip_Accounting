package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject

class AssetGetBalanceTool(private val db: AppDatabase) : AgentTool {
    override val id = "asset.get_balance"
    override val category = "资产"
    override val risk = RiskLevel.READ
    override val description = "查询某资产账户余额"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("assetName", JSONObject().apply {
                put("type", "string")
                put("description", "资产名称，如微信、支付宝、银行卡")
            })
        })
        put("required", org.json.JSONArray().apply { put("assetName") })
    }

    override suspend fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult {
        val assetName = params.optString("assetName", "").trim()
        if (assetName.isEmpty()) {
            return AgentValidationResult.invalidParams("请指定资产名称", listOf("assetName"))
        }

        val assets = context.queryContext.assets
        val matches = assets.filter { it.name.contains(assetName, ignoreCase = true) }
        if (matches.isEmpty()) {
            return AgentValidationResult.notFound("未找到名为「$assetName」的资产账户")
        }
        if (matches.size > 1) {
            val names = matches.joinToString("、") { it.name }
            return AgentValidationResult.ambiguous("找到多个匹配的资产: $names，请更精确地指定")
        }

        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val assetName = params.optString("assetName", "").trim()
        if (assetName.isEmpty()) {
            return AgentToolResult.failure("请指定资产名称")
        }

        val assets = db.assetDao().getAllAssetsList()
        val asset = assets.find { it.name.contains(assetName, ignoreCase = true) }

        return if (asset != null) {
            val formattedBalance = String.format("%.2f", asset.balance)
            AgentToolResult.success(
                facts = JSONObject().apply {
                    put("assetId", asset.id)
                    put("assetName", asset.name)
                    put("balance", asset.balance)
                    put("currency", asset.currency)
                },
                userMessage = "${asset.name}的余额是 $formattedBalance ${asset.currency}"
            )
        } else {
            AgentToolResult.failure("未找到名为「$assetName」的资产账户")
        }
    }
}
