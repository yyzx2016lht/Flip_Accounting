package com.taostudio.tapaccounting.chat.agent

import org.json.JSONObject

object AgentConfirmationController {

    fun shouldConfirm(tool: AgentTool, params: JSONObject): Boolean {
        return when (tool.risk) {
            RiskLevel.READ -> false
            RiskLevel.NAV -> false
            RiskLevel.DESTRUCTIVE -> true
            RiskLevel.SENSITIVE -> true
            RiskLevel.SYSTEM -> true
            RiskLevel.WRITE -> {
                if (tool.id == "bill.create_from_text") {
                    !isSimpleBill(params)
                } else {
                    true
                }
            }
        }
    }

    private fun isSimpleBill(params: JSONObject): Boolean {
        val text = params.optString("text", "")
        if (text.isBlank()) return false
        val amountPattern = Regex("""\d+(\.\d{1,2})?""")
        val matches = amountPattern.findAll(text).toList()
        if (matches.size != 1) return false
        if (text.length >= 50) return false
        val destructiveKeywords = listOf("删除", "删掉", "移除", "覆盖", "批量", "全部", "所有")
        if (destructiveKeywords.any { text.contains(it) }) return false
        return true
    }

    suspend fun buildPreviewMessage(tool: AgentTool, params: JSONObject, db: com.taostudio.tapaccounting.data.local.AppDatabase? = null): String {
        return when (tool.id) {
            "bill.create_from_text" -> {
                val text = params.optString("text", "")
                "将记录: $text"
            }
            "bill.delete" -> {
                val billId = params.optLong("billId", 0)
                if (db != null && billId > 0) {
                    val bill = db.billDao().getBillById(billId)
                    if (bill != null) {
                        val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(bill.time))
                        "将删除账单: ${bill.categoryName} ${String.format("%.2f", bill.amount)}元 $date"
                    } else {
                        "将删除账单 ID: $billId（未找到）"
                    }
                } else {
                    "将删除账单 ID: $billId"
                }
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
