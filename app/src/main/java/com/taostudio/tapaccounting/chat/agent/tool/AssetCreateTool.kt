package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import android.content.Intent
import com.taostudio.tapaccounting.AddAssetActivity
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.agent.UiAction
import org.json.JSONObject

class AssetCreateTool(private val context: Context) : AgentTool {
    override val id = "asset.create"
    override val category = "资产"
    override val risk = RiskLevel.NAV
    override val description = "打开新建资产页面，可预填资产名称和类型"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("name", JSONObject().apply {
                put("type", "string")
                put("description", "资产名称，如：微信、支付宝")
            })
            put("assetType", JSONObject().apply {
                put("type", "string")
                put("description", "资产类型：FUND(资金), CREDIT_CARD(信用卡), RECHARGE(充值), INVESTMENT(投资)")
            })
        })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val name = params.optString("name", "").trim()
        val assetType = params.optString("assetType", "").trim()

        val intent = Intent(this.context, AddAssetActivity::class.java).apply {
            if (name.isNotBlank()) putExtra("preset_name", name)
            if (assetType.isNotBlank()) putExtra("preset_type", assetType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return AgentToolResult.success(
            userMessage = if (name.isNotBlank()) "已打开新建资产页面，预填名称：$name" else "已打开新建资产页面",
            uiAction = UiAction.Navigate(intent)
        )
    }
}
