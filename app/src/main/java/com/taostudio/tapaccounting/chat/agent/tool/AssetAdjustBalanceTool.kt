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

class AssetAdjustBalanceTool(private val context: Context, private val db: AppDatabase) : AgentTool {
    override val id = "asset.adjust_balance"
    override val category = "资产"
    override val risk = RiskLevel.WRITE
    override val description = "打开资产平账页面，调整资产余额"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("assetName", JSONObject().apply {
                put("type", "string")
                put("description", "要平账的资产名称")
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

        // Open BalanceAdjustmentActivity via AddAssetActivity flow
        val intent = Intent(this.context, com.taostudio.tapaccounting.AddAssetActivity::class.java).apply {
            putExtra("edit_asset_id", asset.id)
            putExtra("action", "adjust_balance")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("assetId", asset.id)
                put("assetName", asset.name)
                put("currentBalance", asset.balance)
            },
            userMessage = "已打开「${asset.name}」的平账页面，当前余额：${asset.balance} ${asset.currency}",
            uiAction = UiAction.Navigate(intent)
        )
    }
}
