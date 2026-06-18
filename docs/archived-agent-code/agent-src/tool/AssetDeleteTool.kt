package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.query.QueryAssetOption
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.repository.AssetRepository
import org.json.JSONObject

class AssetDeleteTool(private val db: AppDatabase) : AgentTool {
    override val id = "asset.delete"
    override val category = "资产"
    override val risk = RiskLevel.DESTRUCTIVE
    override val description = "删除资产账户（需确认，关联账单不会被删除）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("assetName", JSONObject().apply {
                put("type", "string")
                put("description", "要删除的资产名称")
            })
        })
        put("required", org.json.JSONArray().apply { put("assetName") })
    }

    override suspend fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult {
        val assetName = params.optString("assetName", "").trim()
        if (assetName.isEmpty()) {
            return AgentValidationResult.invalidParams("请指定资产名称", listOf("assetName"))
        }
        return when (val resolved = resolveAssetOption(assetName, context.queryContext.assets)) {
            is AssetOptionResolveResult.Found -> AgentValidationResult.success()
            is AssetOptionResolveResult.NotFound -> AgentValidationResult.notFound("未找到名为「$assetName」的资产")
            is AssetOptionResolveResult.Ambiguous -> {
                AgentValidationResult.ambiguous("找到多个匹配资产：${resolved.assets.joinToString("、") { it.name }}，请明确指定")
            }
        }
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val assetName = params.optString("assetName", "").trim()
        val asset = when (val resolved = resolveAsset(assetName, db.assetDao().getAllAssetsList())) {
            is AssetResolveResult.Found -> resolved.asset
            is AssetResolveResult.NotFound -> return AgentToolResult.failure("未找到资产「$assetName」")
            is AssetResolveResult.Ambiguous -> {
                return AgentToolResult.failure("找到多个匹配资产：${resolved.assets.joinToString("、") { it.name }}，请明确指定")
            }
        }

        return try {
            val affectedBillCount = db.billDao().getBillsByAssetIdOrNameList(asset.id, asset.name).size
            AssetRepository(db.assetDao(), db.billDao(), db).deleteAssetWithCleanup(asset)
            AgentToolResult.success(
                facts = JSONObject().apply {
                    put("assetId", asset.id)
                    put("assetName", asset.name)
                    put("affectedBillCount", affectedBillCount)
                },
                userMessage = "已删除资产「${asset.name}」。关联账单不会被删除，已解除资产关联并保留账户名快照。" +
                    if (affectedBillCount > 0) " 本次影响 $affectedBillCount 笔账单。" else ""
            )
        } catch (e: Exception) {
            AgentToolResult.failure("删除资产失败：${e.message}")
        }
    }

    private fun resolveAsset(input: String, assets: List<Asset>): AssetResolveResult {
        val exact = assets.filter { it.name.equals(input, ignoreCase = true) }
        if (exact.size == 1) return AssetResolveResult.Found(exact.first())
        if (exact.size > 1) return AssetResolveResult.Ambiguous(exact)

        val fuzzy = assets.filter { it.name.contains(input, ignoreCase = true) }
        return when (fuzzy.size) {
            0 -> AssetResolveResult.NotFound
            1 -> AssetResolveResult.Found(fuzzy.first())
            else -> AssetResolveResult.Ambiguous(fuzzy)
        }
    }

    private fun resolveAssetOption(input: String, assets: List<QueryAssetOption>): AssetOptionResolveResult {
        val exact = assets.filter { it.name.equals(input, ignoreCase = true) }
        if (exact.size == 1) return AssetOptionResolveResult.Found(exact.first())
        if (exact.size > 1) return AssetOptionResolveResult.Ambiguous(exact)

        val fuzzy = assets.filter { it.name.contains(input, ignoreCase = true) }
        return when (fuzzy.size) {
            0 -> AssetOptionResolveResult.NotFound
            1 -> AssetOptionResolveResult.Found(fuzzy.first())
            else -> AssetOptionResolveResult.Ambiguous(fuzzy)
        }
    }

    private sealed class AssetResolveResult {
        data class Found(val asset: Asset) : AssetResolveResult()
        data class Ambiguous(val assets: List<Asset>) : AssetResolveResult()
        data object NotFound : AssetResolveResult()
    }

    private sealed class AssetOptionResolveResult {
        data class Found(val asset: QueryAssetOption) : AssetOptionResolveResult()
        data class Ambiguous(val assets: List<QueryAssetOption>) : AssetOptionResolveResult()
        data object NotFound : AssetOptionResolveResult()
    }
}
