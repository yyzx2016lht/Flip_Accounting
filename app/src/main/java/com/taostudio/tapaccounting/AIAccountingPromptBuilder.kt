package com.taostudio.tapaccounting

import android.content.Context
import com.google.gson.Gson
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.AiRule as DbAiRule

internal fun buildAccountingSystemPrompt(
    ctx: Context,
    promptContext: AIAccountingPromptContext,
    isFromChat: Boolean = false
): String {
    // System prompt 对 Chat/非Chat 完全相同，差异通过 user message 表达 → 缓存 100% 互通
    var prompt = accountingBasePrompt(promptContext.assetFeatureEnabled)
    val hasSecondLevel = hasSecondLevelCategories(promptContext.expenseCats, promptContext.incomeCats)
    prompt = adaptPromptForCategoryDepth(prompt = prompt, hasSecondLevel = hasSecondLevel)

    prompt += AIPrompts.buildTypeRule(promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildExampleAntiLeakRule()
    prompt += AIPrompts.buildRemarksRichnessRule()
    prompt += AIPrompts.buildCategoryRulesCompact(hasSecondLevel)
    prompt += AIPrompts.buildBookFieldRule(promptContext.availableBooks)
    prompt += AIPrompts.buildRepaymentRule(creditCardNames(promptContext), promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildAssetCurrencyRule(assetCurrencyHints(promptContext), promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildAccountingDateRule()

    prompt += AIPrompts.buildReceiptSemanticRule()
    prompt += if (!promptContext.assetFeatureEnabled) {
        AIPrompts.buildNoAssetAccountingRule(promptContext.expenseLeafCats, promptContext.incomeLeafCats)
    } else {
        AIPrompts.buildMultiFastModeRule(promptContext.expenseCats, promptContext.incomeCats)
    }

    prompt += AIPrompts.buildOutputJsonRuleWithTargetFields()

    return renderPromptTemplate(
        prompt = prompt,
        promptContext = promptContext,
        assets = promptContext.assetInfoList,
        expenseCats = promptContext.expenseCats,
        incomeCats = promptContext.incomeCats
    )
}

/**
 * 已废弃：不再支持直接音频记账，音频统一转写后走文本记账流程。
 */
@Deprecated("Audio accounting removed, use buildAccountingSystemPrompt instead")
internal fun buildAudioAccountingSystemPrompt(
    ctx: Context,
    promptContext: AIAccountingPromptContext,
    isFromChat: Boolean = false
): String {
    // System prompt 对 Chat/非Chat 完全相同，差异通过 user message 表达 → 缓存 100% 互通
    var prompt = accountingBasePrompt(promptContext.assetFeatureEnabled)
    val hasSecondLevel = hasSecondLevelCategories(promptContext.expenseCats, promptContext.incomeCats)
    prompt = adaptPromptForCategoryDepth(prompt = prompt, hasSecondLevel = hasSecondLevel)

    prompt += AIPrompts.buildTypeRule(promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildVoiceInputRule()
    prompt += AIPrompts.buildExampleAntiLeakRule()
    prompt += AIPrompts.buildRemarksRichnessRule()
    prompt += AIPrompts.buildCategoryRulesCompact(hasSecondLevel)
    prompt += AIPrompts.buildBookFieldRule(promptContext.availableBooks)
    prompt += AIPrompts.buildRepaymentRule(creditCardNames(promptContext), promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildAssetCurrencyRule(assetCurrencyHints(promptContext), promptContext.assetFeatureEnabled)
    prompt += AIPrompts.buildAccountingDateRule()

    prompt += AIPrompts.buildReceiptSemanticRule()
    prompt += if (!promptContext.assetFeatureEnabled) {
        AIPrompts.buildNoAssetAccountingRule(promptContext.expenseLeafCats, promptContext.incomeLeafCats)
    } else {
        AIPrompts.buildMultiFastModeRule(promptContext.expenseCats, promptContext.incomeCats)
    }

    prompt += AIPrompts.buildOutputJsonRuleWithBookField()

    return renderPromptTemplate(
        prompt = prompt,
        promptContext = promptContext,
        assets = promptContext.assetInfoList,
        expenseCats = promptContext.expenseCats,
        incomeCats = promptContext.incomeCats
    )
}

internal fun buildScreenAccountingSystemPrompt(
    ctx: Context,
    promptContext: AIAccountingPromptContext,
    isFromChat: Boolean = false
): String {
    // 统一使用同一个图片记账 prompt，输出格式差异由 taskInstruction / user message 控制
    var prompt = AIPrompts.IMAGE_ACCOUNTING_PROMPT

    // 动态规则对两个场景通用，且不与基础 prompt 冲突
    val hasSecondLevel = hasSecondLevelCategories(promptContext.expenseCats, promptContext.incomeCats)
    prompt += AIPrompts.buildCategoryRulesCompact(hasSecondLevel)
    prompt += AIPrompts.buildVisualPaymentMethodRule(
        promptContext.assetFeatureEnabled,
        promptContext.assetNames
    )

    // 信用卡还款补充（动态）
    val creditCardNames = creditCardNames(promptContext)
    if (promptContext.assetFeatureEnabled && creditCardNames.isNotEmpty()) {
        prompt += AIPrompts.buildRepaymentRule(creditCardNames, true)
    }

    // 非人民币资产补充（动态）
    val assetCurrencyHints = assetCurrencyHints(promptContext)
    if (promptContext.assetFeatureEnabled && assetCurrencyHints.isNotEmpty()) {
        prompt += AIPrompts.buildAssetCurrencyRule(assetCurrencyHints, true)
    }

    return renderPromptTemplate(
        prompt = prompt,
        promptContext = promptContext,
        assets = promptContext.assetInfoList,
        expenseCats = promptContext.expenseCats,
        incomeCats = promptContext.incomeCats
    )
}

private fun accountingBasePrompt(assetFeatureEnabled: Boolean): String =
    if (assetFeatureEnabled) {
        AIService.MULTI_BILL_PROMPT_DEFAULT
    } else {
        AIPromptsWithoutAccount.MULTI_BILL_PROMPT_DEFAULT
    }

internal fun buildPromptCorrectionBlock(
    matchedRules: List<DbAiRule>,
    includeCategory: Boolean = true,
    includeAccount: Boolean = true
): String {
    if (matchedRules.isEmpty()) return ""
    return buildString {
        appendLine("\n【本地记账习惯修正规则（高优先）】以下规则来自用户自定义，命中后优先遵守：")
        matchedRules.forEachIndexed { index, rule ->
            append("${index + 1}. 关键词：${rule.keyword}")
            rule.targetType?.let { append("；type=$it") }
            if (includeCategory) rule.targetCategory?.takeIf { it.isNotBlank() }?.let { append("；category_name=$it") }
            if (includeAccount) {
                rule.targetAccount1?.takeIf { it.isNotBlank() }?.let { append("；asset_name=$it") }
                rule.targetAccount2?.takeIf { it.isNotBlank() }?.let { append("；to_asset_name=$it") }
            }
            appendLine()
        }
        if (includeAccount) {
            appendLine("命中规则时：优先按规则纠正类型、分类、账户；若规则未覆盖的字段拿不准，可留空，不要猜。")
        } else {
            appendLine("命中规则时：优先按规则纠正类型、分类；当前为无资产模式，禁止要求用户补充账户。")
        }
    }
}

internal fun buildCategoryHierarchyHint(candidates: List<String>): String {
    val parents = candidates.filterNot { it.contains("/::/") || it.contains(" - ") || it.contains(" > ") }
    val children = candidates.filter { it.contains("/::/") || it.contains(" - ") || it.contains(" > ") }
    return buildString {
        if (parents.isNotEmpty()) append("一级分类：${parents.joinToString("、")}。")
        if (children.isNotEmpty()) append("可选二级分类：${children.joinToString("、")}。")
    }
}

internal fun hasSecondLevelCategories(
    expenseCats: List<String>,
    incomeCats: List<String>
): Boolean {
    // Category options may be rendered in different separators:
    // - Prompt candidates are currently built as "一级 - 二级"
    // - Some older paths use "/::/" as an internal separator
    // - Some UI/export paths may use " > "
    // We treat any visible hierarchy separator as second-level existence.
    fun hasHierarchy(list: List<String>): Boolean =
        list.any { it.contains(" - ") || it.contains("/::/") || it.contains(" > ") }
    return hasHierarchy(expenseCats) || hasHierarchy(incomeCats)
}

internal fun adaptPromptForCategoryDepth(prompt: String, hasSecondLevel: Boolean): String {
    val removableKeywords = listOf(
        "优先命中更细的子分类",
        "子分类格式固定为 一级 - 二级",
        "子分类格式必须为 一级 - 二级",
        "子分类格式必须输出 一级 - 二级",
        "一级 - 二级"
    )
    val normalized = prompt
        .lineSequence()
        .filterNot { line -> removableKeywords.any { key -> line.contains(key) } }
        .joinToString("\n")
        .trim()
    val rule = if (hasSecondLevel) {
        "\n【分类层级约束】当前分类库包含二级分类：优先命中更细的子分类；命中子分类时 category_name 必须输出\u201C一级 - 二级\u201D。\n"
    } else {
        "\n【分类层级约束】当前分类库没有二级分类，category_name 只能输出一级分类名；禁止输出\u201C一级 - 二级\u201D格式。\n"
    }
    return normalized + rule
}

private fun creditCardNames(promptContext: AIAccountingPromptContext): List<String> =
    promptContext.dbAssets
        .filter { it.assetCategory == Asset.CATEGORY_CREDIT_CARD }
        .map { it.name }

private fun assetCurrencyHints(promptContext: AIAccountingPromptContext): List<String> =
    promptContext.dbAssets
        .filter { it.currency.isNotEmpty() && it.currency != "CNY" }
        .map { "\"${it.name}\"(${it.currency})" }

internal fun buildAccountingUserPrompt(
    userInput: String,
    currentTimeStr: String,
    matchedPromptRules: List<DbAiRule>,
    assetFeatureEnabled: Boolean,
    isFromChat: Boolean = false,
    aiName: String = ""
): String = buildString {
    // Chat 场景的差异通过 user message 表达，不污染 system prompt → 缓存互通
    if (isFromChat) {
        appendLine("【场景】对话记账模式。你需要理解对话上下文中的指代（如「同上」「刚才那笔」「再来一笔」），并在成功记账后输出 assistant_reply 字段作为对用户的自然语言回复。纯闲聊、追问、寒暄返回 no_bill + reply。")
        if (aiName.isNotBlank()) {
            appendLine("【你的名字】$aiName")
        }
        appendLine("【对话记账输出格式】成功记账：{\"bills\":[...], \"assistant_reply\":\"一句自然的中文回复\"}；非记账/纯闲聊：{\"no_bill\":true, \"reply\":\"...\"}。assistant_reply 必须是直接对用户说的话，不要输出场景标签、英文状态词、JSON 或内部指令。")
    } else {
        appendLine("【场景】独立记账模式。直接输出账单 JSON，不需要 assistant_reply。")
    }
    appendLine("【参考时间】$currentTimeStr")
    if (matchedPromptRules.isNotEmpty()) {
        append(
            buildPromptCorrectionBlock(
                matchedPromptRules,
                includeCategory = true,
                includeAccount = assetFeatureEnabled
            )
        )
    }
    appendLine("【用户输入】")
    append(userInput)
}

internal fun buildScreenAccountingUserText(
    currentTimeStr: String,
    taskInstruction: String
): String = buildString {
    appendLine("【参考时间】$currentTimeStr")
    append(taskInstruction)
}

private fun renderPromptTemplate(
    prompt: String,
    promptContext: AIAccountingPromptContext,
    assets: List<Map<String, String>>,
    expenseCats: List<String>,
    incomeCats: List<String>
): String {
    return prompt
        .replace("{{ASSETS}}", Gson().toJson(assets))
        .replace("{{EXPENSE_CATS}}", Gson().toJson(expenseCats))
        .replace("{{INCOME_CATS}}", Gson().toJson(incomeCats))
        .replace("{{CURRENCIES}}", Gson().toJson(promptContext.currencies))
        .replace("{{TIME}}", promptContext.currentTimeStr)
        .replace("{{DEMO_ASSET}}", promptContext.demoAsset)
        .replace("{{DEMO_EXPENSE_CAT}}", promptContext.demoExpenseCat)
        .replace("{{DEMO_INCOME_CAT}}", promptContext.demoIncomeCat)
}

