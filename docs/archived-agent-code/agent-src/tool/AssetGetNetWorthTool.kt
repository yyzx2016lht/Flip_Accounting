package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject

class AssetGetNetWorthTool(private val db: AppDatabase) : AgentTool {
    override val id = "asset.get_net_worth"
    override val category = "资产"
    override val risk = RiskLevel.READ
    override val description = "查询净资产、总资产、总负债"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val assets = db.assetDao().getAllAssetsList().filterNot { it.isArchived }
        val totalAssets = assets.filter { it.balance >= 0 }.sumOf { it.balance }
        val totalDebts = assets.filter { it.balance < 0 }.sumOf { it.balance }
        val netWorth = totalAssets + totalDebts

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("netWorth", String.format("%.2f", netWorth))
                put("totalAssets", String.format("%.2f", totalAssets))
                put("totalDebts", String.format("%.2f", Math.abs(totalDebts)))
                put("assetCount", assets.size)
            }
        )
    }
}
