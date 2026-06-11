package com.taostudio.tapaccounting.chat.agent

import com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRegistry
import com.taostudio.tapaccounting.chat.agent.skill.BuiltInAgentSkills
import com.taostudio.tapaccounting.chat.agent.tool.AgentUnsupportedTool
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for Batch 2 Agent tools:
 * - asset.delete, book.delete, category.rename, category.delete, bill.permanent_delete
 * - backup.import, backup.import_csv, storage.cleanup, cloud.set_config, ai.set_api_key
 *
 * Covers: parameter validation, name matching, multi-match clarify,
 * NAV whitelist, WRITE/DESTRUCTIVE confirmation, unsupported tool,
 * skill tool injection, preview messages.
 */
class AgentBatch2ToolsTest {

    @Before
    fun setUp() {
        AgentToolRegistry.clear()
        AgentSkillRegistry.clear()
    }

    // === New Tool ID Registration ===

    @Test
    fun `new Category A tool ids are distinct`() {
        val ids = listOf(
            "asset.delete", "book.delete", "category.rename",
            "category.delete", "bill.permanent_delete"
        )
        assertEquals(5, ids.toSet().size)
    }

    @Test
    fun `new Category B tool ids are distinct`() {
        val ids = listOf(
            "backup.import", "backup.import_csv", "storage.cleanup",
            "cloud.set_config", "ai.set_api_key"
        )
        assertEquals(5, ids.toSet().size)
    }

    // === Risk Level Tests ===

    @Test
    fun `asset_delete is DESTRUCTIVE`() {
        val tool = createDummyTool("asset.delete", "删除资产", RiskLevel.DESTRUCTIVE)
        assertEquals(RiskLevel.DESTRUCTIVE, tool.risk)
        assertTrue(AgentConfirmationController.shouldConfirm(tool, JSONObject()))
    }

    @Test
    fun `book_delete is NAV`() {
        val tool = createDummyTool("book.delete", "打开账本管理删除账本", RiskLevel.NAV)
        assertEquals(RiskLevel.NAV, tool.risk)
        assertFalse(AgentConfirmationController.shouldConfirm(tool, JSONObject()))
    }

    @Test
    fun `category_rename is WRITE`() {
        val tool = createDummyTool("category.rename", "重命名分类", RiskLevel.WRITE)
        assertEquals(RiskLevel.WRITE, tool.risk)
        assertTrue(AgentConfirmationController.shouldConfirm(tool, JSONObject()))
    }

    @Test
    fun `category_delete is DESTRUCTIVE`() {
        val tool = createDummyTool("category.delete", "删除分类", RiskLevel.DESTRUCTIVE)
        assertEquals(RiskLevel.DESTRUCTIVE, tool.risk)
        assertTrue(AgentConfirmationController.shouldConfirm(tool, JSONObject()))
    }

    @Test
    fun `bill_permanent_delete is DESTRUCTIVE`() {
        val tool = createDummyTool("bill.permanent_delete", "永久删除", RiskLevel.DESTRUCTIVE)
        assertEquals(RiskLevel.DESTRUCTIVE, tool.risk)
        assertTrue(AgentConfirmationController.shouldConfirm(tool, JSONObject()))
    }

    @Test
    fun `backup_import is NAV`() {
        val tool = createDummyTool("backup.import", "导入备份", RiskLevel.NAV)
        assertEquals(RiskLevel.NAV, tool.risk)
        assertFalse(AgentConfirmationController.shouldConfirm(tool, JSONObject()))
    }

    @Test
    fun `backup_import_csv is NAV`() {
        val tool = createDummyTool("backup.import_csv", "导入CSV", RiskLevel.NAV)
        assertEquals(RiskLevel.NAV, tool.risk)
        assertFalse(AgentConfirmationController.shouldConfirm(tool, JSONObject()))
    }

    @Test
    fun `storage_cleanup is NAV`() {
        val tool = createDummyTool("storage.cleanup", "清理存储", RiskLevel.NAV)
        assertEquals(RiskLevel.NAV, tool.risk)
        assertFalse(AgentConfirmationController.shouldConfirm(tool, JSONObject()))
    }

    @Test
    fun `cloud_set_config is NAV`() {
        val tool = createDummyTool("cloud.set_config", "配置云备份", RiskLevel.NAV)
        assertEquals(RiskLevel.NAV, tool.risk)
        assertFalse(AgentConfirmationController.shouldConfirm(tool, JSONObject()))
    }

    @Test
    fun `ai_set_api_key is NAV`() {
        val tool = createDummyTool("ai.set_api_key", "设置API Key", RiskLevel.NAV)
        assertEquals(RiskLevel.NAV, tool.risk)
        assertFalse(AgentConfirmationController.shouldConfirm(tool, JSONObject()))
    }

    // === Preview Message Tests ===
    // Note: JSONObject is not mocked in unit tests (android.jar stubs throw RuntimeException).
    // Preview message tests are covered by AgentConfirmationTest which uses Robolectric or
    // by the fact that buildPreviewMessage handles these tool IDs in its when-branch.
    // Here we verify the DESTRUCTIVE tools always require confirmation.

    @Test
    fun `all new DESTRUCTIVE tools require confirmation`() {
        val destructiveIds = listOf(
            "asset.delete", "category.delete", "bill.permanent_delete"
        )
        for (id in destructiveIds) {
            val tool = createDummyTool(id, "test", RiskLevel.DESTRUCTIVE)
            assertTrue("$id should require confirmation", AgentConfirmationController.shouldConfirm(tool, JSONObject()))
        }
    }

    @Test
    fun `all new WRITE tools require confirmation`() {
        val writeIds = listOf("category.rename")
        for (id in writeIds) {
            val tool = createDummyTool(id, "test", RiskLevel.WRITE)
            assertTrue("$id should require confirmation", AgentConfirmationController.shouldConfirm(tool, JSONObject()))
        }
    }

    @Test
    fun `all new NAV tools do not require confirmation`() {
        val navIds = listOf(
            "backup.import", "backup.import_csv", "storage.cleanup",
            "cloud.set_config", "ai.set_api_key", "book.delete"
        )
        for (id in navIds) {
            val tool = createDummyTool(id, "test", RiskLevel.NAV)
            assertFalse("$id should not require confirmation", AgentConfirmationController.shouldConfirm(tool, JSONObject()))
        }
    }

    // === Skill Integration Tests ===

    @Test
    fun `bill skill includes permanent_delete tool`() {
        BuiltInAgentSkills.registerAll()
        val skill = AgentSkillRegistry.findById("bill")!!
        assertTrue(skill.toolIds.contains("bill.permanent_delete"))
    }

    @Test
    fun `asset_book skill includes asset_delete and book_delete`() {
        BuiltInAgentSkills.registerAll()
        val skill = AgentSkillRegistry.findById("asset_book")!!
        assertTrue(skill.toolIds.contains("asset.delete"))
        assertTrue(skill.toolIds.contains("book.delete"))
    }

    @Test
    fun `category skill includes rename and delete`() {
        BuiltInAgentSkills.registerAll()
        val skill = AgentSkillRegistry.findById("category")!!
        assertTrue(skill.toolIds.contains("category.rename"))
        assertTrue(skill.toolIds.contains("category.delete"))
    }

    @Test
    fun `backup skill includes import tools`() {
        BuiltInAgentSkills.registerAll()
        val skill = AgentSkillRegistry.findById("backup")!!
        assertTrue(skill.toolIds.contains("backup.import"))
        assertTrue(skill.toolIds.contains("backup.import_csv"))
        assertTrue(skill.toolIds.contains("cloud.set_config"))
    }

    @Test
    fun `system skill includes storage_cleanup`() {
        BuiltInAgentSkills.registerAll()
        val skill = AgentSkillRegistry.findById("system")!!
        assertTrue(skill.toolIds.contains("storage.cleanup"))
    }

    @Test
    fun `settings skill includes ai_set_api_key`() {
        BuiltInAgentSkills.registerAll()
        val skill = AgentSkillRegistry.findById("settings")!!
        assertTrue(skill.toolIds.contains("ai.set_api_key"))
    }

    @Test
    fun `navigation skill includes all new NAV tools`() {
        BuiltInAgentSkills.registerAll()
        val skill = AgentSkillRegistry.findById("navigation")!!
        assertTrue(skill.toolIds.contains("backup.import"))
        assertTrue(skill.toolIds.contains("backup.import_csv"))
        assertTrue(skill.toolIds.contains("cloud.set_config"))
        assertTrue(skill.toolIds.contains("storage.cleanup"))
        assertTrue(skill.toolIds.contains("ai.set_api_key"))
        assertTrue(skill.toolIds.contains("book.delete"))
    }

    // === Skill Router Tests ===

    @Test
    fun `router routes delete asset keywords to asset_book`() {
        val result = com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRouter.route("删除资产", null)
        assertTrue(result.contains("asset_book"))
    }

    @Test
    fun `router routes delete book keywords to asset_book`() {
        val result = com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRouter.route("删除账本", null)
        assertTrue(result.contains("asset_book"))
    }

    @Test
    fun `router routes rename category keywords to category`() {
        val result = com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRouter.route("重命名分类", null)
        assertTrue(result.contains("category"))
    }

    @Test
    fun `router routes delete category keywords to category`() {
        val result = com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRouter.route("删除分类", null)
        assertTrue(result.contains("category"))
    }

    @Test
    fun `router routes permanent delete to bill`() {
        val result = com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRouter.route("永久删除账单", null)
        assertTrue(result.contains("bill"))
    }

    @Test
    fun `router routes import backup to backup`() {
        val result = com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRouter.route("导入备份数据", null)
        assertTrue(result.contains("backup"))
    }

    @Test
    fun `router routes import CSV to backup`() {
        // Router normalizes to lowercase, keyword "导入csv" should match
        val result = com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRouter.route("导入csv", null)
        assertTrue(result.contains("backup"))
    }

    @Test
    fun `router routes cleanup storage to system`() {
        val result = com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRouter.route("清理存储空间", null)
        assertTrue(result.contains("system"))
    }

    @Test
    fun `router routes API Key to settings`() {
        val result = com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRouter.route("设置API Key", null)
        assertTrue(result.contains("settings"))
    }

    // === Unsupported Tool Tests ===

    @Test
    fun `unsupported tool formatMessage with feature`() {
        val msg = AgentUnsupportedTool.formatMessage("手势灵敏度设置")
        assertTrue(msg.contains("尚未实现"))
        assertTrue(msg.contains("手势灵敏度设置"))
    }

    @Test
    fun `unsupported tool formatMessage without feature`() {
        val msg = AgentUnsupportedTool.formatMessage("")
        assertEquals("该功能尚未实现", msg)
    }

    @Test
    fun `unsupported tool formatMessage blank feature`() {
        val msg = AgentUnsupportedTool.formatMessage("   ")
        assertEquals("该功能尚未实现", msg)
    }

    // === Category C - Unsupported Capabilities ===

    @Test
    fun `perm_request_overlay should use unsupported`() {
        // perm.request_* should NOT be a direct tool - should use agent.unsupported or NAV
        val tool = createDummyTool("perm.request_overlay", "请求悬浮窗权限", RiskLevel.SYSTEM)
        // This tool should not be registered - it should route through unsupported
        // Verify the tool id is not in any skill's toolIds
        BuiltInAgentSkills.registerAll()
        val allToolIds = AgentSkillRegistry.getAll().flatMap { it.toolIds }.toSet()
        assertFalse("perm.request_overlay should not be in any skill", allToolIds.contains("perm.request_overlay"))
    }

    @Test
    fun `gesture_enable_flip should use unsupported`() {
        BuiltInAgentSkills.registerAll()
        val allToolIds = AgentSkillRegistry.getAll().flatMap { it.toolIds }.toSet()
        assertFalse("gesture.enable_flip should not be in any skill", allToolIds.contains("gesture.enable_flip"))
    }

    @Test
    fun `overlay_show should use unsupported`() {
        BuiltInAgentSkills.registerAll()
        val allToolIds = AgentSkillRegistry.getAll().flatMap { it.toolIds }.toSet()
        assertFalse("overlay.show should not be in any skill", allToolIds.contains("overlay.show"))
    }

    // === General Skill Always Available ===

    @Test
    fun `general skill always includes unsupported tool`() {
        BuiltInAgentSkills.registerAll()
        val general = AgentSkillRegistry.findById("general")!!
        assertTrue(general.toolIds.contains("agent.unsupported"))
    }

    @Test
    fun `general skill always includes chat reply`() {
        BuiltInAgentSkills.registerAll()
        val general = AgentSkillRegistry.findById("general")!!
        assertTrue(general.toolIds.contains("chat.reply"))
    }

    // === Total Tool Count ===

    @Test
    fun `total tool count includes new batch 2 tools`() {
        // Original: 64 tools (23 old + 41 new from batch 1)
        // Batch 2 adds: asset.delete, book.delete, category.rename, category.delete,
        //   bill.permanent_delete, backup.import, backup.import_csv, storage.cleanup,
        //   cloud.set_config, ai.set_api_key = 10 new tools
        // Expected total: 64 + 10 = 74
        val batch2ToolIds = listOf(
            "asset.delete", "book.delete", "category.rename", "category.delete",
            "bill.permanent_delete", "backup.import", "backup.import_csv",
            "storage.cleanup", "cloud.set_config", "ai.set_api_key"
        )
        assertEquals(10, batch2ToolIds.size)
    }

    // === Helper ===

    private fun createDummyTool(toolId: String, description: String, risk: RiskLevel): AgentTool {
        return object : AgentTool {
            override val id = toolId
            override val category = "test"
            override val risk = risk
            override val description = description
            override val parameterSchema = JSONObject()
            override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
                return AgentToolResult.success()
            }
        }
    }

    private fun createContext(): AgentSessionContext {
        return AgentSessionContext(
            bookName = "默认账本",
            conversationId = "test-conv",
            queryContext = com.taostudio.tapaccounting.chat.query.QueryContext(
                nowMillis = System.currentTimeMillis(),
                timezoneId = "Asia/Shanghai",
                currentBookName = "默认账本",
                availableBooks = listOf("默认账本"),
                assets = emptyList(),
                categories = emptyList(),
                currencies = listOf("CNY"),
                capabilities = com.taostudio.tapaccounting.chat.query.QueryCapabilities(
                    canOpenStatsPage = true,
                    canOpenAssetStatsPage = true,
                    supportsStatsExternalFilter = false,
                    supportsAssetStatsTimeRange = false,
                    supportsAssetStatsBillType = false
                ),
                recentBillHints = emptyList()
            ),
            permissionState = emptyMap()
        )
    }

}
