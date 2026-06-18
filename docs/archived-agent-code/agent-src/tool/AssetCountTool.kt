package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject

class AssetCountTool(private val db: AppDatabase) : AgentTool {
    override val id = "asset.count"
    override val category = "资产"
    override val risk = RiskLevel.READ
    override val description = "查询有多少个资产账户"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val assets = db.assetDao().getAllAssetsList().filterNot { it.isArchived }
        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("count", assets.size)
                put("assets", assets.map { it.name })
            }
        )
    }
}
