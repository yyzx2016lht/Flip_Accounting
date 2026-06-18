package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import android.content.Intent
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.agent.UiAction
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject

class AssetOpenDetailTool(private val context: Context, private val db: AppDatabase) : AgentTool {
    override val id = "asset.open_detail"
    override val category = "资产"
    override val risk = RiskLevel.NAV
    override val description = "打开资产详情页面"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("assetName", JSONObject().apply {
                put("type", "string")
                put("description", "要查看详情的资产名称")
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

        // Navigate to asset detail - using MainActivity with asset tab and asset ID
        val intent = Intent(this.context, com.taostudio.tapaccounting.MainActivity::class.java).apply {
            putExtra("tab", "assets")
            putExtra("open_asset_id", asset.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("assetId", asset.id)
                put("assetName", asset.name)
                put("balance", asset.balance)
                put("currency", asset.currency)
            },
            userMessage = "已打开「${asset.name}」的详情页面",
            uiAction = UiAction.Navigate(intent)
        )
    }
}
