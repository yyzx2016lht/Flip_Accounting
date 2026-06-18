package com.taostudio.tapaccounting.chat.agent

import android.content.Context
import com.taostudio.tapaccounting.chat.agent.tool.*
import com.taostudio.tapaccounting.chat.agent.skill.BuiltInAgentSkills
import com.taostudio.tapaccounting.data.local.AppDatabase

object AgentToolRegistrar {

    fun registerAll(context: Context, db: AppDatabase) {
        // 元工具
        AgentToolRegistry.register(ChatReplyTool())
        AgentToolRegistry.register(AgentClarifyTool())
        AgentToolRegistry.register(AgentListCapabilitiesTool())

        // 统计查询工具
        AgentToolRegistry.register(StatsQueryCategoryTool(db))
        AgentToolRegistry.register(StatsQuerySpendingTool(db))
        AgentToolRegistry.register(StatsQueryMonthSummaryTool(db))
        AgentToolRegistry.register(StatsQueryExistenceTool(db))

        // 资产工具
        AgentToolRegistry.register(AssetListTool(db))
        AgentToolRegistry.register(AssetGetBalanceTool(db))
        AgentToolRegistry.register(AssetCountTool(db))
        AgentToolRegistry.register(AssetGetNetWorthTool(db))

        // 账本工具
        AgentToolRegistry.register(BookGetCurrentTool())
        AgentToolRegistry.register(BookListTool())

        // 记账工具
        AgentToolRegistry.register(BillListRecentTool(db))
        AgentToolRegistry.register(BillSearchTool(db))
        AgentToolRegistry.register(BillGetDetailTool(db))
        AgentToolRegistry.register(BillCreateFromTextTool(context, db))
        AgentToolRegistry.register(BillModifyByInstructionTool(context, db))
        AgentToolRegistry.register(BillDeleteTool(db))
        AgentToolRegistry.register(BillListByDateTool(db))

        // 导航工具
        AgentToolRegistry.register(StatsOpenPageTool(context))

        // 设置工具
        AgentToolRegistry.register(PrefGetTool(context))
        AgentToolRegistry.register(PrefSetTool(context))

        // 注册 Skill
        BuiltInAgentSkills.registerAll()
    }
}
