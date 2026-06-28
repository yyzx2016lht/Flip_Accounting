package com.taostudio.tapaccounting.logic

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.AiRule
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一的规则学习协调器。
 * 从各个入口（表单、聊天、图片等）检测用户编辑行为，生成规则建议并弹窗确认。
 */
object AiRuleSuggestionCoordinator {

    /** 规则建议来源 */
    enum class RuleSuggestionSource {
        FORM, CHAT, IMAGE, VOICE, OVERLAY
    }

    /** 规则建议 */
    data class RuleCreateSuggestion(
        val keyword: String,
        val targetType: Int?,
        val targetCategory: String?,
        val targetAccount1: String?,
        val targetAccount2: String?,
        val source: RuleSuggestionSource,
        val originalRemark: String = ""
    )

    /** 规则保存结果 */
    enum class RuleSaveResult {
        SAVED, UPDATED, SKIPPED, CANCELED
    }

    // 24h 内已提示过的关键词（内存缓存，App 重启后重置）
    private val recentlyPrompted = mutableMapOf<String, Long>()
    private const val PROMPT_COOLDOWN_MS = 24 * 60 * 60 * 1000L

    /**
     * 检测用户编辑是否产生了可学习的规则建议。
     * @param before 编辑前的账单（AI 产出的原始值）
     * @param after 编辑后的账单（用户修改后的值）
     * @return 如果有可学习的变化，返回建议；否则返回 null
     */
    fun detectSuggestion(before: Bill, after: Bill): RuleCreateSuggestion? {
        // 提取关键词：优先用 remark，其次用 categoryName
        val keyword = extractKeyword(before.remark, before.categoryName)
        if (keyword.isBlank()) return null

        // 检测哪些字段发生了变化
        val categoryChanged = before.categoryName != after.categoryName && after.categoryName.isNotBlank()
        val account1Changed = before.accountName != after.accountName && after.accountName.isNotBlank()
        val account2Changed = before.toAccountName != after.toAccountName && after.toAccountName.isNotBlank()
        val typeChanged = before.type != after.type

        // 没有有意义的变化
        if (!categoryChanged && !account1Changed && !account2Changed && !typeChanged) return null

        return RuleCreateSuggestion(
            keyword = keyword,
            targetType = if (typeChanged) after.type else null,
            targetCategory = if (categoryChanged) after.categoryName else null,
            targetAccount1 = if (account1Changed) after.accountName else null,
            targetAccount2 = if (account2Changed) after.toAccountName else null,
            source = RuleSuggestionSource.FORM,
            originalRemark = before.remark
        )
    }

    /**
     * 从聊天编辑中检测规则建议。
     */
    fun detectSuggestionFromChat(
        originalBill: Bill,
        updatedBill: Bill
    ): RuleCreateSuggestion? {
        val keyword = extractKeyword(originalBill.remark, originalBill.categoryName)
        if (keyword.isBlank()) return null

        val categoryChanged = originalBill.categoryName != updatedBill.categoryName
        val account1Changed = originalBill.accountName != updatedBill.accountName
        val account2Changed = originalBill.toAccountName != updatedBill.toAccountName

        if (!categoryChanged && !account1Changed && !account2Changed) return null

        return RuleCreateSuggestion(
            keyword = keyword,
            targetType = null,
            targetCategory = if (categoryChanged) updatedBill.categoryName else null,
            targetAccount1 = if (account1Changed) updatedBill.accountName else null,
            targetAccount2 = if (account2Changed) updatedBill.toAccountName else null,
            source = RuleSuggestionSource.CHAT,
            originalRemark = originalBill.remark
        )
    }

    /**
     * 检查是否应该弹出提示。
     * 条件：
     * 1. 用户开启了"本地规则/提示校正"
     * 2. 24h 内未对同一关键词提示过
     */
    fun shouldPrompt(ctx: Context, suggestion: RuleCreateSuggestion): Boolean {
        if (!Prefs.isAiPromptCorrectionEnabled(ctx) && !Prefs.isLocalRuleOverrideEnabled(ctx)) {
            return false
        }

        val now = System.currentTimeMillis()
        val lastPrompted = recentlyPrompted[suggestion.keyword]
        if (lastPrompted != null && (now - lastPrompted) < PROMPT_COOLDOWN_MS) {
            return false
        }

        return true
    }

    /**
     * 标记关键词已提示（24h 去重）。
     */
    fun markPrompted(keyword: String) {
        recentlyPrompted[keyword] = System.currentTimeMillis()
    }

    /**
     * 清除过期的提示记录。
     */
    fun cleanupPromptCache() {
        val now = System.currentTimeMillis()
        recentlyPrompted.entries.removeAll { (now - it.value) >= PROMPT_COOLDOWN_MS }
    }

    /**
     * 显示规则学习弹窗。
     */
    fun showPrompt(
        activity: Activity,
        suggestion: RuleCreateSuggestion,
        onConfirm: (RuleCreateSuggestion) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        markPrompted(suggestion.keyword)

        val categoryDesc = suggestion.targetCategory ?: ""
        val message = if (categoryDesc.isNotBlank()) {
            activity.getString(R.string.rule_learn_body, suggestion.keyword, categoryDesc)
        } else {
            "以后把「${suggestion.keyword}」的规则记住吗？"
        }

        val dialog = AlertDialog.Builder(ContextThemeWrapper(activity, R.style.Theme_TapAccounting))
            .setTitle(activity.getString(R.string.rule_learn_title))
            .setMessage(message)
            .setPositiveButton(activity.getString(R.string.rule_learn_confirm)) { d, _ ->
                d.dismiss()
                onConfirm(suggestion)
            }
            .setNegativeButton(activity.getString(R.string.rule_learn_cancel)) { d, _ ->
                d.dismiss()
                onCancel?.invoke()
            }
            .setNeutralButton(activity.getString(R.string.rule_learn_no_remind)) { d, _ ->
                d.dismiss()
                // 标记为更长的冷却期（相当于"不再提醒本条"）
                recentlyPrompted[suggestion.keyword] = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
                onCancel?.invoke()
            }
            .setCancelable(true)
            .create()

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = activity,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    /**
     * 持久化规则到数据库。
     * 如果已存在同关键词规则，更新而不是新建。
     */
    suspend fun persistRule(ctx: Context, suggestion: RuleCreateSuggestion): RuleSaveResult {
        return withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(ctx)
            val dao = db.aiRuleDao()

            val keyword = suggestion.keyword.trim()
            val existingRules = dao.getRulesByKeyword(keyword)

            if (existingRules.isNotEmpty()) {
                // 更新现有规则
                val existing = existingRules.first()
                val updated = existing.copy(
                    targetType = suggestion.targetType ?: existing.targetType,
                    targetCategory = suggestion.targetCategory ?: existing.targetCategory,
                    targetAccount1 = suggestion.targetAccount1 ?: existing.targetAccount1,
                    targetAccount2 = suggestion.targetAccount2 ?: existing.targetAccount2
                )
                dao.insertRule(updated)
                RuleSaveResult.UPDATED
            } else {
                // 新建规则
                val newRule = AiRule(
                    keyword = keyword,
                    targetType = suggestion.targetType,
                    targetCategory = suggestion.targetCategory,
                    targetAccount1 = suggestion.targetAccount1,
                    targetAccount2 = suggestion.targetAccount2
                )
                dao.insertRule(newRule)
                RuleSaveResult.SAVED
            }
        }
    }

    /**
     * 从备注中提取关键词。
     * 优先使用备注，如果备注为空则使用分类名。
     */
    private fun extractKeyword(remark: String, categoryName: String): String {
        val cleanRemark = remark.trim()
        if (cleanRemark.isNotBlank()) {
            // 提取核心关键词：去掉常见前缀/后缀
            return cleanRemark
                .replace(Regex("^(今天|昨天|刚才|刚刚|早上|中午|晚上|下午)\\s*"), "")
                .replace(Regex("\\s*(消费|支出|收入|转账|还款)\\s*"), "")
                .trim()
                .takeIf { it.isNotBlank() } ?: cleanRemark
        }
        return categoryName.trim()
    }
}
