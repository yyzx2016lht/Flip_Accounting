package com.taostudio.tapaccounting.chat.agent.skill

import com.taostudio.tapaccounting.chat.agent.AgentSessionContext

object BuiltInAgentSkills {

    val general = object : AgentSkill {
        override val id = "general"
        override val displayName = "通用"
        override val description = "聊天、追问、导航和查看能力"
        override val toolIds = setOf(
            "chat.reply",
            "agent.clarify",
            "agent.list_capabilities",
            "agent.cancel",
            "agent.unsupported",
            "nav.open_stats",
            "nav.open_page"
        )
        override val routingExamples = listOf(
            "你好",
            "你能做什么",
            "打开统计页",
            "打开首页"
        )
    }

    val bill = object : AgentSkill {
        override val id = "bill"
        override val displayName = "账单"
        override val description = "账单的增删改查操作"
        override val toolIds = setOf(
            "bill.list_recent",
            "bill.list_by_date",
            "bill.search",
            "bill.get_detail",
            "bill.create_from_text",
            "bill.create_manual",
            "bill.create_transfer",
            "bill.modify_by_instruction",
            "bill.edit",
            "bill.delete",
            "bill.delete_batch",
            "bill.move_to_book",
            "bill.toggle_exclude_stats",
            "bill.restore_from_bin",
            "bill.refund",
            "bill.permanent_delete"
        )
        override val routingExamples = listOf(
            "午饭花了35",
            "最近三笔账单",
            "删除上一笔",
            "刚才那笔改成40",
            "有没有买过咖啡",
            "删除最近5笔",
            "把那笔转到旅行账本",
            "退款那笔咖啡"
        )

        override fun buildInstructions(context: AgentSessionContext): String {
            return """
## 账单操作说明
- 记账时请使用用户的原始描述
- 修改账单需要明确目标
- 删除账单前请确认用户意图
- 批量删除需要二次确认
- 当前账本: ${context.bookName}
""".trimIndent()
        }
    }

    val stats = object : AgentSkill {
        override val id = "stats"
        override val displayName = "统计"
        override val description = "各种统计查询"
        override val toolIds = setOf(
            "stats.query_category",
            "stats.query_spending",
            "stats.query_month_summary",
            "stats.query_year_summary",
            "stats.query_existence",
            "stats.query_latest_bill",
            "stats.query_compare_period",
            "stats.query_top_categories",
            "stats.open_page",
            "stats.open_asset_page",
            "calendar.query_day",
            "calendar.open",
            "book.query_overview",
            "bill.search",
            "bill.list_by_date"
        )
        override val routingExamples = listOf(
            "本月餐饮花了多少",
            "这个月总花销",
            "法国账本有没有买过红酒",
            "和上个月比呢",
            "本年收支总览",
            "支出最多的分类",
            "昨天买了什么",
            "打开日历"
        )

        override fun buildInstructions(context: AgentSessionContext): String {
            return """
## 统计查询说明
- timeRangeKey 可选值: today, yesterday, this_week, last_week, this_month, last_month, this_year
- 分类名称需要与用户分类匹配
- 当前账本: ${context.bookName}
""".trimIndent()
        }
    }

    val assetBook = object : AgentSkill {
        override val id = "asset_book"
        override val displayName = "资产账本"
        override val description = "资产和账本管理"
        override val toolIds = setOf(
            "asset.list",
            "asset.get_balance",
            "asset.count",
            "asset.get_net_worth",
            "asset.create",
            "asset.archive",
            "asset.unarchive",
            "asset.adjust_balance",
            "asset.open_detail",
            "asset.delete",
            "book.get_current",
            "book.list",
            "book.switch",
            "book.create",
            "book.rename",
            "book.set_default",
            "book.delete"
        )
        override val routingExamples = listOf(
            "微信还有多少钱",
            "我有哪些账户",
            "当前是什么账本",
            "净资产多少",
            "切换到旅行账本",
            "创建一个新账本",
            "收纳那个不用的账户",
            "把日常账本改成生活账本"
        )

        override fun buildInstructions(context: AgentSessionContext): String {
            val assetList = context.queryContext.assets.joinToString("\n") { "  - ${it.name}" }
            return """
## 资产账本说明
- 资产名称需要与以下列表匹配:
$assetList
- 当前账本: ${context.bookName}
""".trimIndent()
        }
    }

    val category = object : AgentSkill {
        override val id = "category"
        override val displayName = "分类"
        override val description = "分类查询和管理"
        override val toolIds = setOf(
            "category.list",
            "category.open_manage",
            "category.rename",
            "category.delete"
        )
        override val routingExamples = listOf(
            "有哪些支出分类",
            "列出收入分类",
            "打开分类管理"
        )

        override fun buildInstructions(context: AgentSessionContext): String {
            return """
## 分类说明
- 使用 category.list 查看支出或收入分类
- 使用 category.open_manage 打开分类管理页面
- 使用 category.rename 重命名分类（会同步更新关联账单）
- 使用 category.delete 删除分类（关联账单的分类将被清空）
""".trimIndent()
        }
    }

    val settings = object : AgentSkill {
        override val id = "settings"
        override val displayName = "设置"
        override val description = "查询和修改设置"
        override val toolIds = setOf(
            "pref.get",
            "pref.set",
            "ai.set_api_key"
        )
        override val routingExamples = listOf(
            "关闭震动",
            "AI模型是什么",
            "切换账本"
        )

        override fun buildInstructions(context: AgentSessionContext): String {
            return """
## 设置操作说明
- 可用的设置项 key:
  - ai_url: AI 服务地址
  - ai_model: AI 模型
  - current_book: 当前账本
  - show_ai_text: 显示 AI 文字
  - show_ai_voice: 显示 AI 语音
  - show_ai_image: 显示 AI 图片
  - multi_bill_enabled: 多账单模式
  - vibrate_feedback: 震动反馈
  - logging_enabled: 日志记录
- pref.set 只能修改以上白名单中的 key
""".trimIndent()
        }
    }

    val backup = object : AgentSkill {
        override val id = "backup"
        override val displayName = "备份"
        override val description = "数据备份与恢复"
        override val toolIds = setOf(
            "backup.list_modules",
            "backup.export_full",
            "backup.export_csv",
            "backup.import",
            "backup.import_csv",
            "cloud.get_config",
            "cloud.open_settings",
            "cloud.set_config"
        )
        override val routingExamples = listOf(
            "备份数据",
            "导出CSV",
            "云备份设置",
            "备份了哪些模块"
        )
    }

    val navigation = object : AgentSkill {
        override val id = "navigation"
        override val displayName = "导航"
        override val description = "打开应用内各页面"
        override val toolIds = setOf(
            "nav.open_page",
            "nav.open_stats",
            "stats.open_page",
            "stats.open_asset_page",
            "calendar.open",
            "asset.open_detail",
            "asset.create",
            "book.delete",
            "category.open_manage",
            "bill.edit",
            "bill.create_manual",
            "backup.export_full",
            "backup.export_csv",
            "backup.import",
            "backup.import_csv",
            "cloud.open_settings",
            "cloud.set_config",
            "storage.open",
            "storage.cleanup",
            "ai.set_api_key"
        )
        override val routingExamples = listOf(
            "打开首页",
            "打开设置",
            "打开备份页面",
            "打开回收站",
            "打开日历",
            "打开账本总览"
        )
    }

    val system = object : AgentSkill {
        override val id = "system"
        override val displayName = "系统"
        override val description = "系统状态查询"
        override val toolIds = setOf(
            "perm.get_status",
            "gesture.get_status",
            "gesture.list_actions",
            "storage.get_usage",
            "storage.open",
            "storage.cleanup"
        )
        override val routingExamples = listOf(
            "存储空间还有多少",
            "权限状态",
            "手势功能开启了吗",
            "有哪些手势动作"
        )
    }

    fun registerAll() {
        AgentSkillRegistry.register(general)
        AgentSkillRegistry.register(bill)
        AgentSkillRegistry.register(stats)
        AgentSkillRegistry.register(assetBook)
        AgentSkillRegistry.register(category)
        AgentSkillRegistry.register(settings)
        AgentSkillRegistry.register(backup)
        AgentSkillRegistry.register(navigation)
        AgentSkillRegistry.register(system)
    }
}
