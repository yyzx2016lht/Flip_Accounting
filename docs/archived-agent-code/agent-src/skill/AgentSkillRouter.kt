package com.taostudio.tapaccounting.chat.agent.skill

import com.taostudio.tapaccounting.Logger
import android.content.Context

object AgentSkillRouter {
    private const val LOG_TAG = "AgentSkillRouter"

    private val localRoutingRules = mapOf(
        "bill" to listOf("记账", "记一笔", "花了", "买了", "支付", "付款", "收入", "转账", "还款", "退款", "删除账单", "修改账单", "账单", "最近几笔", "搜索账单", "批量删除", "移动账单", "恢复账单", "手动记账", "永久删除"),
        "stats" to listOf("花了多少", "总花销", "分类花销", "月度统计", "年度统计", "比较", "查询", "统计", "汇总", "排行", "环比", "同比", "日历"),
        "asset_book" to listOf("余额", "资产", "账户", "账本", "净资产", "多少钱", "有多少钱", "切换账本", "创建账本", "重命名", "收纳", "平账", "默认账本", "删除资产", "删除账本"),
        "category" to listOf("分类", "支出分类", "收入分类", "分类管理", "重命名分类", "删除分类"),
        "settings" to listOf("设置", "震动", "通知", "显示", "偏好", "配置", "模型", "api", "api key"),
        "backup" to listOf("备份", "恢复", "导出", "csv", "云备份", "webdav", "导入备份", "导入csv", "导入数据"),
        "navigation" to listOf("打开", "跳转", "进入", "去", "导航", "回到首页", "打开页面"),
        "system" to listOf("权限", "手势", "翻转", "双击", "三击", "存储空间", "存储占用", "悬浮窗", "清理存储", "清理缓存")
    )

    fun route(userText: String, context: Context?): List<String> {
        val normalized = userText.trim().lowercase()
        if (normalized.isBlank()) return listOf("general")

        // Special case: navigation + stats -> general + stats
        val navKeywords = listOf("打开", "跳转", "进入", "去")
        if (navKeywords.any { normalized.contains(it) } && normalized.contains("统计")) {
            return listOf("general", "stats")
        }

        val matchedSkills = mutableSetOf<String>()
        for ((skillId, keywords) in localRoutingRules) {
            if (keywords.any { normalized.contains(it) }) {
                matchedSkills.add(skillId)
            }
        }

        if (matchedSkills.isEmpty()) {
            return listOf("general")
        }

        if (matchedSkills.size > 3) {
            val priority = listOf("bill", "stats", "asset_book", "category", "backup", "settings", "system", "navigation")
            val sorted = matchedSkills.sortedBy { skill ->
                priority.indexOf(skill).let { if (it < 0) 999 else it }
            }
            return sorted.take(3)
        }

        return matchedSkills.toList()
    }

    fun routeWithFallback(userText: String, context: Context?): List<String> {
        val routed = route(userText, context)
        return if (routed.isEmpty()) listOf("general") else routed
    }
}
