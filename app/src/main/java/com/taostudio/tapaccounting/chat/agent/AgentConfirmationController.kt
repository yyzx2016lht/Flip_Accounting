package com.taostudio.tapaccounting.chat.agent

import org.json.JSONObject

object AgentConfirmationController {

    fun shouldConfirm(tool: AgentTool, params: JSONObject): Boolean {
        return when {
            tool.risk == RiskLevel.READ -> false
            tool.risk == RiskLevel.DESTRUCTIVE -> true
            tool.risk == RiskLevel.SENSITIVE -> true
            tool.risk == RiskLevel.SYSTEM -> true
            tool.id == "bill.create_from_text" -> {
                val text = params.optString("text", "")
                isSimpleBill(text)
            }
            tool.risk == RiskLevel.WRITE -> true
            tool.risk == RiskLevel.NAV -> false
            else -> true
        }
    }

    private fun isSimpleBill(text: String): Boolean {
        val amountPattern = Regex("""\d+(\.\d{1,2})?""")
        val matches = amountPattern.findAll(text).toList()
        return matches.size <= 1 && text.length < 50
    }

    fun buildPreviewMessage(tool: AgentTool, params: JSONObject): String {
        return when (tool.id) {
            "bill.create_from_text" -> {
                val text = params.optString("text", "")
                "将记录: $text"
            }
            "bill.delete" -> {
                val billId = params.optLong("billId", 0)
                "将删除账单 ID: $billId"
            }
            "bill.delete_batch" -> {
                val billIds = params.optJSONArray("billIds")
                "将批量删除 ${billIds?.length() ?: 0} 笔账单"
            }
            "bill.modify_by_instruction" -> {
                val instruction = params.optString("instruction", "")
                "将修改账单: $instruction"
            }
            "asset.delete" -> {
                val assetId = params.optLong("assetId", 0)
                "将删除资产 ID: $assetId"
            }
            "book.delete" -> {
                val bookName = params.optString("bookName", "")
                "将删除账本: $bookName"
            }
            "backup.import", "backup.import_csv" -> {
                "将导入数据，这会覆盖现有数据"
            }
            else -> {
                "将执行: ${tool.description}"
            }
        }
    }
}
