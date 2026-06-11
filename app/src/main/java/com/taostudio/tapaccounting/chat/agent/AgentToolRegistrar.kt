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
        AgentToolRegistry.register(AgentCancelTool())
        AgentToolRegistry.register(AgentUnsupportedTool())

        // 统计查询工具
        AgentToolRegistry.register(StatsQueryCategoryTool(db))
        AgentToolRegistry.register(StatsQuerySpendingTool(db))
        AgentToolRegistry.register(StatsQueryMonthSummaryTool(db))
        AgentToolRegistry.register(StatsQueryExistenceTool(db))
        AgentToolRegistry.register(StatsQueryLatestBillTool(db))
        AgentToolRegistry.register(StatsQueryYearSummaryTool(db))
        AgentToolRegistry.register(StatsQueryComparePeriodTool(db))
        AgentToolRegistry.register(StatsQueryTopCategoriesTool(db))
        AgentToolRegistry.register(StatsOpenPageTool(context))
        AgentToolRegistry.register(StatsOpenAssetPageTool(context))

        // 日历工具
        AgentToolRegistry.register(CalendarQueryDayTool(db))
        AgentToolRegistry.register(CalendarOpenTool(context))

        // 资产工具
        AgentToolRegistry.register(AssetListTool(db))
        AgentToolRegistry.register(AssetGetBalanceTool(db))
        AgentToolRegistry.register(AssetCountTool(db))
        AgentToolRegistry.register(AssetGetNetWorthTool(db))
        AgentToolRegistry.register(AssetCreateTool(context))
        AgentToolRegistry.register(AssetArchiveTool(db))
        AgentToolRegistry.register(AssetUnarchiveTool(db))
        AgentToolRegistry.register(AssetAdjustBalanceTool(context, db))
        AgentToolRegistry.register(AssetOpenDetailTool(context, db))
        AgentToolRegistry.register(AssetDeleteTool(db))

        // 账本工具
        AgentToolRegistry.register(BookGetCurrentTool())
        AgentToolRegistry.register(BookListTool())
        AgentToolRegistry.register(BookSwitchTool(context))
        AgentToolRegistry.register(BookCreateTool(context))
        AgentToolRegistry.register(BookRenameTool(context))
        AgentToolRegistry.register(BookSetDefaultTool(context))
        AgentToolRegistry.register(BookQueryOverviewTool(db))
        AgentToolRegistry.register(BookDeleteTool(context, db))

        // 分类工具
        AgentToolRegistry.register(CategoryListTool())
        AgentToolRegistry.register(CategoryOpenManageTool(context))
        AgentToolRegistry.register(CategoryRenameTool(db))
        AgentToolRegistry.register(CategoryDeleteTool(db))

        // 记账工具
        AgentToolRegistry.register(BillListRecentTool(db))
        AgentToolRegistry.register(BillSearchTool(db))
        AgentToolRegistry.register(BillGetDetailTool(db))
        AgentToolRegistry.register(BillCreateFromTextTool(context, db))
        AgentToolRegistry.register(BillCreateManualTool(context))
        AgentToolRegistry.register(BillCreateTransferTool(context, db))
        AgentToolRegistry.register(BillModifyByInstructionTool(context, db))
        AgentToolRegistry.register(BillDeleteTool(db))
        AgentToolRegistry.register(BillDeleteBatchTool(db))
        AgentToolRegistry.register(BillEditTool(context, db))
        AgentToolRegistry.register(BillMoveToBookTool(db))
        AgentToolRegistry.register(BillToggleExcludeStatsTool(db))
        AgentToolRegistry.register(BillRestoreFromBinTool(db))
        AgentToolRegistry.register(BillRefundTool(context, db))
        AgentToolRegistry.register(BillListByDateTool(db))
        AgentToolRegistry.register(BillPermanentDeleteTool(db))

        // 导航工具
        AgentToolRegistry.register(NavOpenStatsTool(context))
        AgentToolRegistry.register(NavOpenPageTool(context))

        // 备份工具
        AgentToolRegistry.register(BackupListModulesTool())
        AgentToolRegistry.register(BackupExportFullTool(context))
        AgentToolRegistry.register(BackupExportCsvTool(context))
        AgentToolRegistry.register(CloudGetConfigTool(context))
        AgentToolRegistry.register(CloudOpenSettingsTool(context))
        AgentToolRegistry.register(BackupImportTool(context))
        AgentToolRegistry.register(BackupImportCsvTool(context))
        AgentToolRegistry.register(CloudSetConfigTool(context))

        // 系统工具
        AgentToolRegistry.register(StorageGetUsageTool(context))
        AgentToolRegistry.register(StorageOpenTool(context))
        AgentToolRegistry.register(PermGetStatusTool(context))
        AgentToolRegistry.register(GestureGetStatusTool(context))
        AgentToolRegistry.register(GestureListActionsTool())
        AgentToolRegistry.register(StorageCleanupTool(context))

        // 设置工具
        AgentToolRegistry.register(PrefGetTool(context))
        AgentToolRegistry.register(PrefSetTool(context))
        AgentToolRegistry.register(AiSetApiKeyTool(context))

        // 注册 Skill
        BuiltInAgentSkills.registerAll()
    }
}
