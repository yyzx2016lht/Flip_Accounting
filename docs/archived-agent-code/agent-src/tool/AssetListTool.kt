package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject

class AssetListTool(private val db: AppDatabase) : AgentTool {
    override val id = "asset.list"
    override val category = "资产"
    override val risk = RiskLevel.READ
    override val description = "列出所有资产账户"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("includeArchived", JSONObject().apply {
                put("type", "boolean")
                put("description", "是否包含已收纳的资产，默认false")
            })
        })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val includeArchived = params.optBoolean("includeArchived", false)

        val assets = db.assetDao().getAllAssetsList()
        val filtered = if (includeArchived) assets else assets.filterNot { it.isArchived }

        if (filtered.isEmpty()) {
            return AgentToolResult.success(
                userMessage = "暂无资产账户"
            )
        }

        val sb = StringBuilder("资产账户列表：\n")
        for (asset in filtered) {
            val status = if (asset.isArchived) "（已收纳）" else ""
            sb.appendLine("• ${asset.name}${status}: ${asset.balance} ${asset.currency}")
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("count", filtered.size)
                put("assets", filtered.map {
                    JSONObject().apply {
                        put("id", it.id)
                        put("name", it.name)
                        put("balance", it.balance)
                        put("currency", it.currency)
                    }
                })
            },
            userMessage = sb.toString().trim()
        )
    }
}
