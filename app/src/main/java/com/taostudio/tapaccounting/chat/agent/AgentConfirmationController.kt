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
                requiresBillCreateConfirmation(text)
            }
            tool.risk == RiskLevel.WRITE -> true
            tool.risk == RiskLevel.NAV -> false
            else -> true
        }
    }

    fun requiresBillCreateConfirmation(text: String): Boolean {
        val amountPattern = Regex("""\d+(\.\d{1,2})?""")
        val matches = amountPattern.findAll(text).toList()
        return matches.size != 1 || text.length >= 50
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
            "backup.import", "backup.import_csv" -> {
                "将导入数据，这会覆盖现有数据"
            }
            "bill.move_to_book" -> {
                val billId = params.optLong("billId", 0)
                val target = params.optString("targetBookName", "")
                "将账单 $billId 移动到「$target」"
            }
            "bill.toggle_exclude_stats" -> {
                val billId = params.optLong("billId", 0)
                val exclude = params.optBoolean("exclude", false)
                "将账单 $billId ${if (exclude) "排除" else "计入"}统计"
            }
            "bill.restore_from_bin" -> {
                val billId = params.optLong("billId", 0)
                "将从回收站恢复账单 ID: $billId"
            }
            "bill.refund" -> {
                val billId = params.optLong("billId", 0)
                val amount = params.optDouble("refundAmount", 0.0)
                "将对账单 $billId 退款 ${String.format("%.2f", amount)} 元"
            }
            "bill.create_transfer" -> {
                val text = params.optString("text", "")
                "将记录转账: $text"
            }
            "book.switch" -> {
                val bookName = params.optString("bookName", "")
                "将切换到账本「$bookName」"
            }
            "book.create" -> {
                val bookName = params.optString("bookName", "")
                "将创建新账本「$bookName」"
            }
            "book.rename" -> {
                val oldName = params.optString("oldName", "")
                val newName = params.optString("newName", "")
                "将「$oldName」重命名为「$newName」"
            }
            "book.set_default" -> {
                val bookName = params.optString("bookName", "")
                "将「$bookName」设为默认账本"
            }
            "asset.archive" -> {
                val name = params.optString("assetName", "")
                "将收纳资产「$name」"
            }
            "asset.unarchive" -> {
                val name = params.optString("assetName", "")
                "将取消收纳资产「$name」"
            }
            "asset.delete" -> {
                val name = params.optString("assetName", "")
                "将删除资产「$name」，保留账单并解除资产关联"
            }
            "category.rename" -> {
                val oldName = params.optString("oldName", "")
                val newName = params.optString("newName", "")
                "将分类「$oldName」重命名为「$newName」"
            }
            "category.delete" -> {
                val name = params.optString("categoryName", "")
                "将删除分类「$name」及其子分类，关联账单的分类将被清空"
            }
            "bill.permanent_delete" -> {
                val billId = params.optLong("billId", 0)
                "将从回收站永久删除账单 ID: $billId（不可恢复）"
            }
            else -> {
                "将执行: ${tool.description}"
            }
        }
    }
}
