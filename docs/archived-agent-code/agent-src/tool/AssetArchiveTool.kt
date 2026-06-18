package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject

class AssetArchiveTool(private val db: AppDatabase) : AgentTool {
    override val id = "asset.archive"
    override val category = "资产"
    override val risk = RiskLevel.WRITE
    override val description = "收纳（归档）资产账户"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("assetName", JSONObject().apply {
                put("type", "string")
                put("description", "要收纳的资产名称")
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
            return AgentValidationResult.notFound("未找到名为「$assetName」的资产")
        }
        if (matches.size > 1) {
            return AgentValidationResult.ambiguous("找到多个匹配资产：${matches.joinToString("、") { it.name }}，请明确指定")
        }
        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val assetName = params.optString("assetName", "").trim()
        val asset = db.assetDao().getAllAssetsList().find { it.name.contains(assetName, ignoreCase = true) }
            ?: return AgentToolResult.failure("未找到资产「$assetName」")

        if (asset.isArchived) {
            return AgentToolResult.success(userMessage = "资产「${asset.name}」已经是收纳状态")
        }

        db.assetDao().updateArchived(asset.id, true)
        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("assetId", asset.id)
                put("assetName", asset.name)
            },
            userMessage = "已收纳资产「${asset.name}」"
        )
    }
}
