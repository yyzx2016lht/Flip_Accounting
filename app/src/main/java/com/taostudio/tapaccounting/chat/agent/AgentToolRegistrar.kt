package com.taostudio.tapaccounting.chat.agent

import android.content.Context
import com.taostudio.tapaccounting.chat.agent.tool.*
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

        // 资产工具
        AgentToolRegistry.register(AssetListTool(db))
        AgentToolRegistry.register(AssetGetBalanceTool(db))

        // 账本工具
        AgentToolRegistry.register(BookGetCurrentTool())

        // 记账工具
        AgentToolRegistry.register(BillListRecentTool(db))
        AgentToolRegistry.register(BillSearchTool(db))
        AgentToolRegistry.register(BillGetDetailTool(db))
        AgentToolRegistry.register(BillCreateFromTextTool(context, db))

        // 导航工具
        AgentToolRegistry.register(NavOpenStatsTool(context))

        // 设置工具
        AgentToolRegistry.register(PrefGetTool(context))
        AgentToolRegistry.register(PrefSetTool(context))
    }
}
